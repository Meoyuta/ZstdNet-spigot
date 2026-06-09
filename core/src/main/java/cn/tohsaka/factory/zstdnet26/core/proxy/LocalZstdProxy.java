package cn.tohsaka.factory.zstdnet26.core.proxy;

import cn.tohsaka.factory.zstdnet26.core.io.CountingInputStream;
import cn.tohsaka.factory.zstdnet26.core.io.CountingOutputStream;
import cn.tohsaka.factory.zstdnet26.core.io.StreamTransfer;
import cn.tohsaka.factory.zstdnet26.core.protocol.HandshakePacket;
import cn.tohsaka.factory.zstdnet26.core.protocol.PacketIo;
import cn.tohsaka.factory.zstdnet26.core.protocol.ZstdFrameCodec;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class LocalZstdProxy {
    private static final int ZSTD_STREAM_BUFFER_SIZE = 16 * 1024;
    private static final AtomicInteger WORKER_SEQ = new AtomicInteger(1);
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(new NamedFactory("zstdnet-client-worker"));

    private LocalZstdProxy() {
    }

    public static ProxyHandle start(
        String remoteHost,
        int remotePort,
        String presentedHost,
        int presentedPort,
        int level,
        ProxyLogger logger
    ) throws IOException {
        Objects.requireNonNull(remoteHost, "remoteHost");
        Objects.requireNonNull(presentedHost, "presentedHost");
        Objects.requireNonNull(logger, "logger");

        ServerSocket listener = new ServerSocket();
        listener.bind(new InetSocketAddress("127.0.0.1", 0));
        AtomicBoolean running = new AtomicBoolean(true);
        ClientProxyStats stats = new ClientProxyStats();
        Thread acceptThread = new Thread(
            () -> acceptLoop(listener, running, remoteHost, remotePort, presentedHost, presentedPort, level, stats, logger),
            "zstdnet-client-accept-" + remoteHost + "-" + remotePort
        );
        acceptThread.setDaemon(true);
        acceptThread.start();
        logger.info("client proxy started: 127.0.0.1:" + listener.getLocalPort() + " -> " + remoteHost + ":" + remotePort);
        return new ProxyHandle(listener, running, acceptThread, remoteHost, remotePort, stats, logger);
    }

    private static void acceptLoop(
        ServerSocket listener,
        AtomicBoolean running,
        String remoteHost,
        int remotePort,
        String presentedHost,
        int presentedPort,
        int level,
        ClientProxyStats stats,
        ProxyLogger logger
    ) {
        while (running.get()) {
            try {
                Socket localClient = listener.accept();
                WORKERS.execute(() -> handleConnection(
                    localClient,
                    remoteHost,
                    remotePort,
                    presentedHost,
                    presentedPort,
                    level,
                    stats,
                    logger
                ));
            } catch (SocketException e) {
                if (running.get()) {
                    logger.warn("client proxy accept socket closed: " + e);
                }
                return;
            } catch (Exception e) {
                if (running.get()) {
                    logger.warn("client proxy accept failed: " + e);
                }
            }
        }
    }

    private static void handleConnection(
        Socket localClient,
        String remoteHost,
        int remotePort,
        String presentedHost,
        int presentedPort,
        int level,
        ClientProxyStats stats,
        ProxyLogger logger
    ) {
        try (Socket client = localClient) {
            client.setTcpNoDelay(true);
            byte[] firstPacket = PacketIo.readPacket(client.getInputStream());
            byte[] rewritten = HandshakePacket.rewriteDestination(firstPacket, presentedHost, presentedPort);
            HandshakePacket handshake = HandshakePacket.parse(firstPacket);
            boolean status = handshake != null && handshake.nextState() == HandshakePacket.STATUS;

            try (Socket upstream = new Socket()) {
                upstream.connect(new InetSocketAddress(remoteHost, remotePort), 5000);
                upstream.setTcpNoDelay(true);
                if (status) {
                    forwardRaw(client, upstream, rewritten, stats);
                } else {
                    forwardZstd(client, upstream, rewritten, level, stats);
                }
            }
        } catch (Exception e) {
            logger.warn("client proxy connection failed: " + e);
        }
    }

    private static void forwardRaw(Socket client, Socket upstream, byte[] firstPacket, ClientProxyStats stats) throws Exception {
        OutputStream upstreamOut = new CountingOutputStream(upstream.getOutputStream(), bytes -> {
            stats.addRawUp(bytes);
            stats.addWireUp(bytes);
        });
        PacketIo.writePacket(upstreamOut, firstPacket);
        upstreamOut.flush();

        Future<?> up = WORKERS.submit(() -> {
            try {
                StreamTransfer.copyAndFlush(client.getInputStream(), upstreamOut);
            } catch (Exception ignored) {
            } finally {
                shutdownOutput(upstream);
            }
        });
        Future<?> down = WORKERS.submit(() -> {
            try {
                StreamTransfer.copyAndFlush(
                    new CountingInputStream(upstream.getInputStream(), bytes -> {
                        stats.addRawDown(bytes);
                        stats.addWireDown(bytes);
                    }),
                    client.getOutputStream()
                );
            } catch (Exception ignored) {
            } finally {
                shutdownOutput(client);
            }
        });
        waitForPipes(client, upstream, up, down);
    }

    private static void forwardZstd(Socket client, Socket upstream, byte[] firstPacket, int level, ClientProxyStats stats) throws Exception {
        OutputStream upstreamOut = upstream.getOutputStream();
        byte[] firstPacketWire = toPacketWire(firstPacket);
        byte[] firstFrame = ZstdFrameCodec.compressFrame(firstPacketWire, level);
        upstreamOut.write(ZstdFrameCodec.MAGIC);
        upstreamOut.write(firstFrame);
        upstreamOut.flush();
        stats.addRawUp(firstPacketWire.length);
        stats.addWireUp(ZstdFrameCodec.MAGIC.length + firstFrame.length);

        Future<?> up = WORKERS.submit(() -> {
            try {
                byte[] buffer = new byte[ZSTD_STREAM_BUFFER_SIZE];
                while (!client.isClosed() && !upstream.isClosed()) {
                    int read = client.getInputStream().read(buffer);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }
                    byte[] raw = Arrays.copyOf(buffer, read);
                    byte[] frame = ZstdFrameCodec.compressFrame(raw, level);
                    stats.addRawUp(raw.length);
                    stats.addWireUp(frame.length);
                    upstreamOut.write(frame);
                    upstreamOut.flush();
                }
            } catch (Exception ignored) {
            } finally {
                shutdownOutput(upstream);
            }
        });
        Future<?> down = WORKERS.submit(() -> {
            try {
                OutputStream clientOut = client.getOutputStream();
                while (!client.isClosed() && !upstream.isClosed()) {
                    byte[] packet = ZstdFrameCodec.readFrame(upstream.getInputStream());
                    stats.addRawDown(packet.length);
                    clientOut.write(packet);
                    clientOut.flush();
                }
            } catch (Exception ignored) {
            } finally {
                shutdownOutput(client);
            }
        });
        waitForPipes(client, upstream, up, down);
    }

    private static byte[] toPacketWire(byte[] payload) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(payload.length + 5);
        PacketIo.writePacket(out, payload);
        return out.toByteArray();
    }

    private static void waitForPipes(Socket client, Socket upstream, Future<?> up, Future<?> down) throws Exception {
        while (!up.isDone() && !down.isDone()) {
            Thread.sleep(20L);
        }
        closeQuietly(client);
        closeQuietly(upstream);
        up.get();
        down.get();
    }

    private static void shutdownOutput(Socket socket) {
        try {
            socket.shutdownOutput();
        } catch (Exception ignored) {
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    public record ProxyHandle(
        ServerSocket listener,
        AtomicBoolean running,
        Thread acceptThread,
        String remoteHost,
        int remotePort,
        ClientProxyStats stats,
        ProxyLogger logger
    ) implements AutoCloseable {
        public int localPort() {
            return listener.getLocalPort();
        }

        @Override
        public void close() {
            running.set(false);
            try {
                listener.close();
            } catch (IOException ignored) {
            }
            if (acceptThread != null && acceptThread != Thread.currentThread()) {
                try {
                    acceptThread.join(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            logger.info("client proxy stopped: " + remoteHost + ":" + remotePort);
        }
    }

    public static final class ClientProxyStats {
        private final cn.tohsaka.factory.zstdnet26.core.stats.TrafficStats stats = new cn.tohsaka.factory.zstdnet26.core.stats.TrafficStats();

        public void addRawUp(long bytes) {
            stats.addRawUp(bytes);
        }

        public void addRawDown(long bytes) {
            stats.addRawDown(bytes);
        }

        public void addWireUp(long bytes) {
            stats.addWireUp(bytes);
        }

        public void addWireDown(long bytes) {
            stats.addWireDown(bytes);
        }

        public cn.tohsaka.factory.zstdnet26.core.stats.TrafficStats.Snapshot snapshot() {
            return stats.snapshot();
        }
    }

    private static final class NamedFactory implements ThreadFactory {
        private final String prefix;

        private NamedFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + WORKER_SEQ.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
