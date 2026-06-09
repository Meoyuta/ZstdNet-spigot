package cn.tohsaka.factory.zstdnet26.client;

import cn.tohsaka.factory.zstdnet26.core.netty.ZstdFrameStats;
import cn.tohsaka.factory.zstdnet26.core.netty.ZstdNettyPipeline;
import cn.tohsaka.factory.zstdnet26.core.proxy.ProxyLogger;
import io.netty.channel.ChannelPipeline;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public final class ZstdNetConnectionHooks {
    private static final long PENDING_CONNECT_TTL_MS = 15_000L;
    private static final AtomicReference<PendingConnection> PENDING = new AtomicReference<>();

    private ZstdNetConnectionHooks() {
    }

    public static boolean prepare(String host, int port) {
        if (host == null || host.isBlank()) {
            PENDING.set(null);
            return false;
        }

        ClientConfig config = ZstdNetClient.config();
        if (!config.enabledFor(host, port)) {
            PENDING.set(null);
            return false;
        }

        PendingConnection pending = new PendingConnection(
            host.toLowerCase(Locale.ROOT),
            port,
            config.compressionLevel(),
            System.currentTimeMillis() + PENDING_CONNECT_TTL_MS
        );
        PENDING.set(pending);
        ZstdNetClient.logger().info("prepared ZstdNet pipeline for " + host + ":" + port);
        return true;
    }

    public static void install(ChannelPipeline pipeline) {
        PendingConnection pending = PENDING.getAndSet(null);
        if (pending == null || pending.expired()) {
            return;
        }

        ZstdNettyPipeline.install(pipeline, pending.compressionLevel(), true, ZstdFrameStats.NONE);
        ZstdNetClient.logger().info("installed ZstdNet pipeline for " + pending.host() + ":" + pending.port());
    }

    public static void reposition(ChannelPipeline pipeline) {
        if (pipeline == null || pipeline.get(ZstdNettyPipeline.INBOUND_HANDLER) == null) {
            return;
        }

        try {
            ZstdNettyPipeline.reposition(pipeline);
        } catch (RuntimeException e) {
            ProxyLogger logger = ZstdNetClient.logger();
            logger.warn("failed to reposition ZstdNet pipeline: " + e);
        }
    }

    private record PendingConnection(String host, int port, int compressionLevel, long expiresAtMs) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAtMs;
        }
    }
}
