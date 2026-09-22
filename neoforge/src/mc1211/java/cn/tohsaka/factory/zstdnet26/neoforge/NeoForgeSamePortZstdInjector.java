package cn.tohsaka.factory.zstdnet26.neoforge;

import cn.tohsaka.factory.zstdnet26.core.ZstdNetConfig;
import cn.tohsaka.factory.zstdnet26.core.dictionary.ZstdDictionaryStore;
import cn.tohsaka.factory.zstdnet26.core.dictionary.ZstdDictionaryTrainer;
import cn.tohsaka.factory.zstdnet26.core.proxy.ProxyLogger;
import cn.tohsaka.factory.zstdnet26.core.stats.TrafficStats;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class NeoForgeSamePortZstdInjector implements AutoCloseable {
    private static final String ACCEPT_HANDLER = "zstdnet-accept-injector";
    private static final String CONNECTION_HANDLER = "zstdnet-same-port-codec";
    private static final VarHandle CHANNELS = resolveChannelsHandle();

    private final MinecraftServer minecraftServer;
    private final ZstdNetConfig config;
    private final ProxyLogger logger;
    private final ZstdDictionaryStore dictionaryStore;
    private final ZstdDictionaryTrainer dictionaryTrainer;
    private final TrafficStats stats = new TrafficStats();
    private final List<Channel> injectedServerChannels = new ArrayList<>();

    NeoForgeSamePortZstdInjector(
        MinecraftServer minecraftServer,
        ZstdNetConfig config,
        ProxyLogger logger,
        ZstdDictionaryStore dictionaryStore,
        ZstdDictionaryTrainer dictionaryTrainer
    ) {
        this.minecraftServer = Objects.requireNonNull(minecraftServer, "minecraftServer");
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dictionaryStore = Objects.requireNonNull(dictionaryStore, "dictionaryStore");
        this.dictionaryTrainer = Objects.requireNonNull(dictionaryTrainer, "dictionaryTrainer");
    }

    void inject() {
        List<Channel> serverChannels = serverChannels();
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

    @Override
    public void close() {
        for (Channel channel : injectedServerChannels) {
            if (!channel.isOpen()) {
                continue;
            }
            channel.eventLoop().submit(() -> {
                if (channel.pipeline().get(ACCEPT_HANDLER) != null) {
                    channel.pipeline().remove(ACCEPT_HANDLER);
                }
            }).syncUninterruptibly();
        }
        injectedServerChannels.clear();
        logger.info("ZstdNet same-port injection stopped");
    }

    private List<Channel> serverChannels() {
        ServerConnectionListener connectionListener = minecraftServer.getConnection();
        List<Channel> channels = new ArrayList<>();
        for (ChannelFuture future : channelFutures(connectionListener)) {
            if (future != null && future.channel() != null) {
                channels.add(future.channel());
            }
        }
        return channels;
    }

    @SuppressWarnings("unchecked")
    private static List<ChannelFuture> channelFutures(ServerConnectionListener connectionListener) {
        return (List<ChannelFuture>) CHANNELS.get(connectionListener);
    }

    private static VarHandle resolveChannelsHandle() {
        try {
            return MethodHandles.privateLookupIn(ServerConnectionListener.class, MethodHandles.lookup())
                .findVarHandle(ServerConnectionListener.class, "channels", List.class)
                .withInvokeExactBehavior();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final class AcceptInjector extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Channel child && child.pipeline().get(CONNECTION_HANDLER) == null) {
                child.pipeline().addFirst(
                    CONNECTION_HANDLER,
                    new NeoForgeSamePortZstdHandler(config, stats, logger, dictionaryStore, dictionaryTrainer)
                );
            }
            super.channelRead(ctx, msg);
        }
    }
}
