package cn.tohsaka.factory.zstdnet26.core.proxy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class FloodGuard {
    private final Map<String, GuardEntry> entries = new ConcurrentHashMap<>();
    private final int maxConnections;
    private final int maxRequests;
    private final Duration window;
    private final Duration banDuration;

    FloodGuard(ZstdProxyConfig config) {
        this.maxConnections = config.maxConnectionsPerIp();
        this.maxRequests = config.maxRequestsPerWindow();
        this.window = config.requestWindow();
        this.banDuration = config.banDuration();
    }

    synchronized boolean begin(String ip) {
        long now = System.currentTimeMillis();
        GuardEntry entry = entries.computeIfAbsent(ip, ignored -> new GuardEntry());
        prune(entry, now);
        if (entry.bannedUntilMs > now) {
            return false;
        }
        if (maxRequests > 0 && !window.isZero() && !window.isNegative()) {
            entry.requests.addLast(now);
            if (entry.requests.size() > maxRequests) {
                entry.bannedUntilMs = now + Math.max(0L, banDuration.toMillis());
                return false;
            }
        }
        if (maxConnections > 0 && entry.activeConnections >= maxConnections) {
            return false;
        }
        entry.activeConnections++;
        return true;
    }

    synchronized void end(String ip) {
        GuardEntry entry = entries.get(ip);
        if (entry == null) {
            return;
        }
        if (entry.activeConnections > 0) {
            entry.activeConnections--;
        }
        long now = System.currentTimeMillis();
        prune(entry, now);
        if (entry.activeConnections == 0 && entry.requests.isEmpty() && entry.bannedUntilMs <= now) {
            entries.remove(ip);
        }
    }

    private void prune(GuardEntry entry, long now) {
        if (window.isZero() || window.isNegative()) {
            entry.requests.clear();
            return;
        }
        long cutoff = now - window.toMillis();
        while (!entry.requests.isEmpty() && entry.requests.peekFirst() < cutoff) {
            entry.requests.removeFirst();
        }
    }

    private static final class GuardEntry {
        private int activeConnections;
        private long bannedUntilMs;
        private final Deque<Long> requests = new ArrayDeque<>();
    }
}
