package cn.tohsaka.factory.zstdnet26.core.proxy;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UdpForwarder implements AutoCloseable {
    private static final int BUFFER_SIZE = 65535;
    private static final long SESSION_TIMEOUT_MS = 60_000L;

    private final UdpRoute route;
    private final ProxyLogger logger;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<SocketAddress, UdpSession> sessions = new ConcurrentHashMap<>();
    private DatagramSocket listenSocket;
    private Thread forwardThread;

    public UdpForwarder(UdpRoute route, ProxyLogger logger) {
        this.route = route;
        this.logger = logger;
    }

    public void start() throws IOException {
        listenSocket = new DatagramSocket(null);
        listenSocket.setReuseAddress(true);
        listenSocket.bind(route.listen().toAddress());
        listenSocket.setSoTimeout(1000);
        running.set(true);
        forwardThread = new Thread(this::forwardLoop, "zstdnet-udp-" + route.name());
        forwardThread.setDaemon(true);
        forwardThread.start();
        logger.info("UDP route started [" + route.name() + "]: " + route.listen() + " -> " + route.target());
    }

    @Override
    public void close() {
        running.set(false);
        if (listenSocket != null) {
            listenSocket.close();
        }
        sessions.values().forEach(UdpSession::close);
        sessions.clear();
        join(forwardThread);
    }

    private void forwardLoop() {
        byte[] buffer = new byte[BUFFER_SIZE];
        InetSocketAddress target = route.target().toAddress();
        long lastSweep = System.currentTimeMillis();
        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    listenSocket.receive(packet);
                } catch (SocketTimeoutException ignored) {
                    sweep();
                    continue;
                }
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, data.length);
                sessionFor(packet.getSocketAddress(), target).send(data, target);
                long now = System.currentTimeMillis();
                if (now - lastSweep > 10_000L) {
                    sweep();
                    lastSweep = now;
                }
            } catch (SocketException e) {
                if (running.get()) {
                    logger.warn("UDP socket closed [" + route.name() + "]: " + e);
                }
                return;
            } catch (IOException e) {
                if (running.get()) {
                    logger.warn("UDP forward error [" + route.name() + "]: " + e);
                }
            }
        }
    }

    private UdpSession sessionFor(SocketAddress clientAddress, InetSocketAddress target) throws IOException {
        UdpSession existing = sessions.get(clientAddress);
        if (existing != null) {
            return existing;
        }
        UdpSession created = new UdpSession(clientAddress, target);
        UdpSession raced = sessions.putIfAbsent(clientAddress, created);
        if (raced != null) {
            created.close();
            return raced;
        }
        created.start();
        return created;
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            UdpSession session = entry.getValue();
            if (now - session.lastActivityMs <= SESSION_TIMEOUT_MS) {
                return false;
            }
            session.close();
            return true;
        });
    }

    private final class UdpSession {
        private final SocketAddress clientAddress;
        private final DatagramSocket upstreamSocket;
        private final InetSocketAddress target;
        private volatile long lastActivityMs = System.currentTimeMillis();
        private Thread returnThread;

        private UdpSession(SocketAddress clientAddress, InetSocketAddress target) throws SocketException {
            this.clientAddress = clientAddress;
            this.target = target;
            this.upstreamSocket = new DatagramSocket();
            this.upstreamSocket.setSoTimeout(1000);
        }

        private void start() {
            returnThread = new Thread(this::returnLoop, "zstdnet-udp-return-" + route.name());
            returnThread.setDaemon(true);
            returnThread.start();
        }

        private void send(byte[] data, InetSocketAddress ignoredTarget) throws IOException {
            lastActivityMs = System.currentTimeMillis();
            upstreamSocket.send(new DatagramPacket(data, data.length, target));
        }

        private void returnLoop() {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (running.get() && !upstreamSocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        upstreamSocket.receive(packet);
                    } catch (SocketTimeoutException ignored) {
                        if (System.currentTimeMillis() - lastActivityMs > SESSION_TIMEOUT_MS) {
                            break;
                        }
                        continue;
                    }
                    lastActivityMs = System.currentTimeMillis();
                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), packet.getOffset(), data, 0, data.length);
                    listenSocket.send(new DatagramPacket(data, data.length, clientAddress));
                } catch (IOException e) {
                    if (running.get() && !upstreamSocket.isClosed()) {
                        logger.warn("UDP return error [" + route.name() + "]: " + e);
                    }
                    break;
                }
            }
            sessions.remove(clientAddress);
            close();
        }

        private void close() {
            upstreamSocket.close();
            join(returnThread);
        }
    }

    private static void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
