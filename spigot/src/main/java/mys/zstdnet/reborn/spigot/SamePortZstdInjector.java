package mys.zstdnet.reborn.spigot;

import mys.zstdnet.reborn.core.proxy.ProxyLogger;
import mys.zstdnet.reborn.core.ZstdNetConfig;
import mys.zstdnet.reborn.core.stats.TrafficStats;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryStore;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryTrainer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import org.bukkit.Bukkit;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class SamePortZstdInjector implements AutoCloseable {
    private static final String ACCEPT_HANDLER = "zstdnet-accept-injector";
    private static final String CONNECTION_HANDLER = "zstdnet-same-port-codec";

    private final ZstdNetConfig config;
    private final ProxyLogger logger;
    private final ZstdDictionaryStore dictionaryStore;
    private final ZstdDictionaryTrainer dictionaryTrainer;
    private final TrafficStats stats = new TrafficStats();
    private final List<Channel> injectedServerChannels = new ArrayList<>();

    SamePortZstdInjector(
        ZstdNetConfig config,
        ProxyLogger logger,
        ZstdDictionaryStore dictionaryStore,
        ZstdDictionaryTrainer dictionaryTrainer
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dictionaryStore = dictionaryStore;
        this.dictionaryTrainer = dictionaryTrainer;
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

    ZstdNetConfig config() {
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
                child.pipeline().addFirst(CONNECTION_HANDLER, new SamePortZstdHandler(config, stats, logger, dictionaryStore, dictionaryTrainer));
            }
            super.channelRead(ctx, msg);
        }
    }

    private static List<Channel> findServerChannels() throws Exception {
        try {
            Object server = (Object) ServerAccess.SERVER.invokeExact((Object) Bukkit.getServer());
            Object listener = (Object) ServerAccess.CONNECTION.invokeExact(server);
            List<?> futures = (List<?>) ServerAccess.CHANNELS.invokeExact(listener);
            List<Channel> channels = new ArrayList<>();
            synchronized (futures) {
                for (Object value : futures) {
                    if (value instanceof ChannelFuture future) {
                        channels.add(future.channel());
                    }
                }
            }
            return channels;
        } catch (Throwable e) {
            throw new IllegalStateException("Could not access Minecraft listener channels", e);
        }
    }

    // Resolve once, lazily at startup. Connections only use the Netty handlers above.
    private static final class ServerAccess {
        private static final MethodHandle SERVER;
        private static final MethodHandle CONNECTION;
        private static final MethodHandle CHANNELS;

        static {
            try {
                Method serverMethod = Bukkit.getServer().getClass().getMethod("getServer");
                SERVER = unreflect(serverMethod).asType(MethodType.methodType(Object.class, Object.class));
                Class<?> serverType = serverMethod.getReturnType();
                Method connectionMethod = connectionMethod(serverType);
                CONNECTION = unreflect(connectionMethod).asType(MethodType.methodType(Object.class, Object.class));
                Class<?> listenerType = connectionMethod.getReturnType();
                Field channels = channelsField(listenerType);
                CHANNELS = MethodHandles.privateLookupIn(channels.getDeclaringClass(), MethodHandles.lookup())
                    .unreflectGetter(channels).asType(MethodType.methodType(List.class, Object.class));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static MethodHandle unreflect(Method method) throws IllegalAccessException {
            return MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup())
                .unreflect(method);
        }

        private static Method connectionMethod(Class<?> type) throws NoSuchMethodException {
            try {
                return type.getMethod("getConnection");
            } catch (NoSuchMethodException ignored) {
                // Older Spigot mappings rename members but retain the listener class name.
                for (Method method : type.getMethods()) {
                    String name = method.getReturnType().getSimpleName();
                    if (method.getParameterCount() == 0
                        && (name.equals("ServerConnectionListener") || name.equals("ServerConnection"))) {
                        return method;
                    }
                }
                throw new NoSuchMethodException(type.getName() + "#getConnection");
            }
        }

        private static Field channelsField(Class<?> type) throws NoSuchFieldException {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField("channels");
                    if (List.class.isAssignableFrom(field.getType())) {
                        return field;
                    }
                } catch (NoSuchFieldException ignored) {
                }
                for (Field field : current.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(field.getType())
                        && field.getGenericType().getTypeName().contains("io.netty.channel.ChannelFuture")) {
                        return field;
                    }
                }
            }
            throw new NoSuchFieldException(type.getName() + "#channels");
        }
    }
}
