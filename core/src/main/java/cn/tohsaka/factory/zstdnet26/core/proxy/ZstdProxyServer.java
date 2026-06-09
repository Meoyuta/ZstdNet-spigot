package cn.tohsaka.factory.zstdnet26.core.proxy;

import cn.tohsaka.factory.zstdnet26.core.io.CountingInputStream;
import cn.tohsaka.factory.zstdnet26.core.io.CountingOutputStream;
import cn.tohsaka.factory.zstdnet26.core.io.StreamTransfer;
import cn.tohsaka.factory.zstdnet26.core.limit.TokenBucketLimiter;
import cn.tohsaka.factory.zstdnet26.core.protocol.ByteArrayOps;
import cn.tohsaka.factory.zstdnet26.core.protocol.HandshakePacket;
import cn.tohsaka.factory.zstdnet26.core.protocol.PacketIo;
import cn.tohsaka.factory.zstdnet26.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet26.core.stats.TrafficStats;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ZstdProxyServer implements AutoCloseable {
    private static final int PEEK_BUFFER = 4096;
    private static final int MAX_HANDSHAKE_WIRE = 4096;
    private static final byte[] ZSTD_MAGIC = new byte[]{0x28, (byte) 0xB5, 0x2F, (byte) 0xFD};

    private final Object lock = new Object();
    private final ProxyLogger logger;

    private volatile boolean running;
    private ServerSocket listener;
    private Thread acceptThread;
    private ExecutorService workers;
    private ScheduledExecutorService statsTicker;
    private FloodGuard guard;
    private TokenBucketLimiter globalLimiter;
    private final TrafficStats stats = new TrafficStats();
    private ZstdProxyConfig config;
    private List<UdpForwarder> udpForwarders = List.of();

    public ZstdProxyServer(ProxyLogger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void start(ZstdProxyConfig newConfig) throws IOException {
        Objects.requireNonNull(newConfig, "newConfig");
        synchronized (lock) {
            if (running) {
                return;
            }
            if (!newConfig.enabled()) {
                logger.warn("ZstdNet proxy config is disabled");
                return;
            }

            ServerSocket socket = new ServerSocket();
            socket.bind(newConfig.listen().toAddress());
            socket.setReuseAddress(true);

            config = newConfig;
            listener = socket;
            guard = new FloodGuard(newConfig);
            globalLimiter = TokenBucketLimiter.create(newConfig.maxRateGlobalBps(), newConfig.burstBytes());
            workers = Executors.newCachedThreadPool(new NamedFactory("zstdnet-worker"));
            statsTicker = Executors.newSingleThreadScheduledExecutor(new NamedFactory("zstdnet-stats"));
            running = true;

            acceptThread = new Thread(this::acceptLoop, "zstdnet-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            startUdpForwarders(newConfig);
            startStatsTicker(newConfig);

            logger.info("ZstdNet proxy started: " + newConfig.listen() + " -> " + newConfig.target());
        }
    }

    public boolean isRunning() {
        return running;
    }

    public TrafficStats.Snapshot snapshot() {
        return stats.snapshot();
    }

    public ZstdProxyConfig config() {
        return config;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (!running) {
                return;
            }
            running = false;
            closeUdpForwarders();
            closeQuietly(listener);
            listener = null;
            join(acceptThread, 1000L);
            acceptThread = null;
            shutdown(statsTicker);
            shutdown(workers);
            statsTicker = null;
            workers = null;
            guard = null;
            globalLimiter = null;
            config = null;
            logger.info("ZstdNet proxy stopped");
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = listener.accept();
                workers.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (running) {
                    logger.warn("accept error: " + e);
                }
            }
        }
    }

    private void handleClient(Socket client) {
        String sourceIp = sourceIp(client.getRemoteSocketAddress());
        stats.addConnection(1);
        try (Socket clientSocket = client) {
            if (!guard.begin(sourceIp)) {
                logger.warn("blocked by flood guard: " + sourceIp);
                return;
            }
            try {
                clientSocket.setTcpNoDelay(true);
                PushbackInputStream clientIn = new PushbackInputStream(clientSocket.getInputStream(), PEEK_BUFFER);
                ClientMode detected = detectMode(clientIn);
                if (detected.kind == ModeKind.RAW_LOGIN) {
                    logger.warn("rejected raw login from " + sourceIp);
                    sendLoginDisconnect(clientSocket.getOutputStream(), config.rawLoginMessage());
                    return;
                }

                try (Socket upstream = new Socket()) {
                    upstream.connect(config.target().toAddress(), 5000);
                    upstream.setTcpNoDelay(true);
                    if (detected.kind == ModeKind.RAW_STATUS) {
                        forwardRaw(clientSocket, clientIn, upstream, detected.initialWire);
                    } else {
                        forwardZstd(clientSocket, clientIn, upstream);
                    }
                }
            } finally {
                guard.end(sourceIp);
            }
        } catch (Exception e) {
            if (isRealError(e)) {
                logger.warn("connection error from " + sourceIp + ": " + e);
            }
        } finally {
            stats.addConnection(-1);
        }
    }

    private ClientMode detectMode(PushbackInputStream input) throws IOException {
        byte[] first = input.readNBytes(4);
        if (first.length == 0) {
            return ClientMode.zstd();
        }
        input.unread(first);
        if (startsWith(first, ZSTD_MAGIC)) {
            return ClientMode.zstd();
        }

        byte[] firstPacket = PacketIo.readPacketWire(input, MAX_HANDSHAKE_WIRE);
        byte[] payload = PacketIo.extractPacketPayload(firstPacket);
        HandshakePacket handshake = HandshakePacket.parse(payload);
        if (handshake == null) {
            input.unread(firstPacket);
            return ClientMode.zstd();
        }
        if (handshake.nextState() == HandshakePacket.STATUS) {
            return new ClientMode(ModeKind.RAW_STATUS, firstPacket);
        }
        if (handshake.nextState() == HandshakePacket.LOGIN) {
            return new ClientMode(ModeKind.RAW_LOGIN, firstPacket);
        }
        input.unread(firstPacket);
        return ClientMode.zstd();
    }

    private void forwardRaw(Socket client, PushbackInputStream clientIn, Socket upstream, byte[] firstPacketWire) throws Exception {
        OutputStream upstreamOut = new CountingOutputStream(upstream.getOutputStream(), bytes -> {
            stats.addRawUp(bytes);
            stats.addWireUp(bytes);
        });
        upstreamOut.write(firstPacketWire);
        upstreamOut.flush();

        Future<?> up = workers.submit(() -> {
            try {
                StreamTransfer.copyAndFlush(clientIn, upstreamOut);
            } catch (Exception ignored) {
            } finally {
                shutdownOutput(upstream);
            }
        });
        Future<?> down = workers.submit(() -> {
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

    private void forwardZstd(Socket client, PushbackInputStream clientIn, Socket upstream) throws Exception {
        TokenBucketLimiter perConnectionLimiter = TokenBucketLimiter.create(config.maxRatePerConnectionBps(), config.burstBytes());
        Future<?> up = workers.submit(() -> {
            try (ZstdInputStream zstdIn = new ZstdInputStream(
                new CountingInputStream(clientIn, stats::addWireUp)
            )) {
                StreamTransfer.copyAndFlush(
                    zstdIn,
                    new CountingOutputStream(upstream.getOutputStream(), stats::addRawUp)
                );
            } catch (Exception ignored) {
            } finally {
                shutdownOutput(upstream);
            }
        });
        Future<?> down = workers.submit(() -> {
            try (ZstdOutputStream zstdOut = new ZstdOutputStream(
                new RateLimitedOutputStream(
                    new CountingOutputStream(client.getOutputStream(), stats::addWireDown),
                    perConnectionLimiter,
                    globalLimiter
                ),
                config.compressionLevel()
            )) {
                zstdOut.setCloseFrameOnFlush(false);
                StreamTransfer.copyAndFlush(
                    new CountingInputStream(upstream.getInputStream(), stats::addRawDown),
                    zstdOut,
                    config.flushInterval()
                );
            } catch (Exception ignored) {
            } finally {
                shutdownOutput(client);
            }
        });
        waitForPipes(client, upstream, up, down);
    }

    private void waitForPipes(Socket client, Socket upstream, Future<?> up, Future<?> down) throws Exception {
        while (!up.isDone() && !down.isDone()) {
            Thread.sleep(20L);
        }
        closeQuietly(client);
        closeQuietly(upstream);
        up.get();
        down.get();
    }

    private void sendLoginDisconnect(OutputStream out, String message) throws IOException {
        String escaped = message == null ? "ZstdNet required" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        byte[] json = ("{\"text\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8);
        byte[] payload = ByteArrayOps.concat(
            VarIntCodec.encode(0),
            VarIntCodec.encode(json.length),
            json
        );
        PacketIo.writePacket(out, payload);
        out.flush();
    }

    private void startUdpForwarders(ZstdProxyConfig newConfig) {
        List<UdpForwarder> started = new ArrayList<>();
        for (UdpRoute route : newConfig.udpRoutes()) {
            try {
                UdpForwarder forwarder = new UdpForwarder(route, logger);
                forwarder.start();
                started.add(forwarder);
            } catch (Exception e) {
                logger.warn("UDP route skipped [" + route.name() + "]: " + e);
            }
        }
        udpForwarders = started;
    }

    private void closeUdpForwarders() {
        for (UdpForwarder forwarder : udpForwarders) {
            try {
                forwarder.close();
            } catch (Exception ignored) {
            }
        }
        udpForwarders = List.of();
    }

    private void startStatsTicker(ZstdProxyConfig newConfig) {
        if (newConfig.statsInterval() == null || newConfig.statsInterval().isZero() || newConfig.statsInterval().isNegative()) {
            return;
        }
        statsTicker.scheduleAtFixedRate(() -> {
            TrafficStats.Snapshot snap = stats.snapshot();
            logger.info(String.format(Locale.ROOT,
                "stats active=%d total=%d rawUp=%d rawDown=%d wireUp=%d wireDown=%d ratio=%.2f%%",
                snap.connections(),
                snap.totalConnections(),
                snap.rawUpBytes(),
                snap.rawDownBytes(),
                snap.wireUpBytes(),
                snap.wireDownBytes(),
                snap.ratioPercent()
            ));
        }, newConfig.statsInterval().toMillis(), newConfig.statsInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String sourceIp(SocketAddress address) {
        String raw = String.valueOf(address);
        if (raw.startsWith("/")) {
            raw = raw.substring(1);
        }
        int slash = raw.indexOf('/');
        if (slash >= 0) {
            raw = raw.substring(slash + 1);
        }
        int colon = raw.lastIndexOf(':');
        return colon > 0 ? raw.substring(0, colon) : raw;
    }

    private static boolean isRealError(Exception e) {
        if (e == null) {
            return false;
        }
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return !(message.contains("socket closed") || message.contains("connection reset") || message.contains("broken pipe"));
    }

    private static void shutdownOutput(Socket socket) {
        try {
            socket.shutdownOutput();
        } catch (Exception ignored) {
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static void shutdown(ExecutorService service) {
        if (service == null) {
            return;
        }
        service.shutdownNow();
        try {
            service.awaitTermination(1L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void join(Thread thread, long millis) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private enum ModeKind {
        ZSTD,
        RAW_STATUS,
        RAW_LOGIN
    }

    private record ClientMode(ModeKind kind, byte[] initialWire) {
        private static ClientMode zstd() {
            return new ClientMode(ModeKind.ZSTD, null);
        }
    }

    private static final class NamedFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger index = new AtomicInteger(1);

        private NamedFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
