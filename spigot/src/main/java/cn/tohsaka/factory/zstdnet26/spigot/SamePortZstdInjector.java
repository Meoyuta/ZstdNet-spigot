package cn.tohsaka.factory.zstdnet26.spigot;

import cn.tohsaka.factory.zstdnet26.core.proxy.ProxyLogger;
import cn.tohsaka.factory.zstdnet26.core.proxy.ZstdProxyConfig;
import cn.tohsaka.factory.zstdnet26.core.stats.TrafficStats;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

final class SamePortZstdInjector implements AutoCloseable {
    private static final String ACCEPT_HANDLER = "zstdnet-accept-injector";
    private static final String CONNECTION_HANDLER = "zstdnet-same-port-codec";

    private final ZstdProxyConfig config;
    private final ProxyLogger logger;
    private final TrafficStats stats = new TrafficStats();
    private final List<Channel> injectedServerChannels = new ArrayList<>();

    SamePortZstdInjector(ZstdProxyConfig config, ProxyLogger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    void inject() throws Exception {
        List<Channel> serverChannels = findServerChannels();
        if (serverChannels.isEmpty()) {
            throw new IllegalStateException("could not find Minecraft server Netty channels");
        }

        for (Channel channel : serverChannels) {
            channel.eventLoop().submit(() -> {
                if (channel.pipeline().get(ACCEPT_HANDLER) == null) {
                    channel.pipeline().addFirst(ACCEPT_HANDLER, new AcceptInjector());
                }
            }).syncUninterruptibly();
            injectedServerChannels.add(channel);
        }
        logger.info("ZstdNet same-port injection active on " + serverChannels.size() + " server channel(s)");
    }

    TrafficStats.Snapshot snapshot() {
        return stats.snapshot();
    }

    ZstdProxyConfig config() {
        return config;
    }

    @Override
    public void close() {
        for (Channel channel : injectedServerChannels) {
            if (channel.isOpen()) {
                channel.eventLoop().submit(() -> {
                    if (channel.pipeline().get(ACCEPT_HANDLER) != null) {
                        channel.pipeline().remove(ACCEPT_HANDLER);
                    }
                }).syncUninterruptibly();
            }
        }
        injectedServerChannels.clear();
        logger.info("ZstdNet same-port injection stopped");
    }

    private final class AcceptInjector extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Channel child && child.pipeline().get(CONNECTION_HANDLER) == null) {
                child.pipeline().addFirst(CONNECTION_HANDLER, new SamePortZstdHandler(config, stats, logger));
            }
            super.channelRead(ctx, msg);
        }
    }

    private static List<Channel> findServerChannels() throws Exception {
        Object minecraftServer = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Object serverConnection = findServerConnection(minecraftServer);
        if (serverConnection == null) {
            return List.of();
        }
        return findChannels(serverConnection);
    }

    private static Object findServerConnection(Object minecraftServer) throws Exception {
        for (Method method : minecraftServer.getClass().getMethods()) {
            try {
                if (method.getParameterCount() == 0 && method.getReturnType().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("connection")) {
                    Object value = method.invoke(minecraftServer);
                    if (value != null && !findChannels(value).isEmpty()) {
                        return value;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        for (Field field : allFields(minecraftServer.getClass())) {
            try {
                field.setAccessible(true);
                Object value = field.get(minecraftServer);
                if (value != null && value.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("connection")
                    && !findChannels(value).isEmpty()) {
                    return value;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private static List<Channel> findChannels(Object serverConnection) throws Exception {
        List<Channel> channels = new ArrayList<>();
        for (Field field : allFields(serverConnection.getClass())) {
            try {
                field.setAccessible(true);
                Object value = field.get(serverConnection);
                collectChannels(value, channels);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return channels;
    }

    private static void collectChannels(Object value, List<Channel> channels) {
        if (value instanceof Channel channel) {
            channels.add(channel);
            return;
        }
        if (value instanceof ChannelFuture future) {
            channels.add(future.channel());
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectChannels(item, channels);
            }
        }
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            fields.addAll(List.of(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }
}
