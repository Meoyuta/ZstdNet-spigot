package cn.tohsaka.factory.zstdnet26.neoforge;

import cn.tohsaka.factory.zstdnet26.client.ZstdNetClient;
import cn.tohsaka.factory.zstdnet26.core.HostPort;
import cn.tohsaka.factory.zstdnet26.core.ZstdNetConfig;
import cn.tohsaka.factory.zstdnet26.core.dictionary.ZstdDictionaryStore;
import cn.tohsaka.factory.zstdnet26.core.dictionary.ZstdDictionaryTrainer;
import cn.tohsaka.factory.zstdnet26.core.proxy.ProxyLogger;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

@Mod(ZstdNetNeoForge.MOD_ID)
public final class ZstdNetNeoForge {
    public static final String MOD_ID = "zstdnet";

    private static final Logger LOGGER = LoggerFactory.getLogger("ZstdNet");

    private final ProxyLogger proxyLogger = new Slf4jProxyLogger(LOGGER);
    private NeoForgeSamePortZstdInjector injector;
    private ZstdDictionaryTrainer dictionaryTrainer;

    public ZstdNetNeoForge() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ZstdNetClient.init(FMLPaths.CONFIGDIR.get(), proxyLogger);
        }

        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private synchronized void onServerStarted(ServerStartedEvent event) {
        if (injector != null) {
            return;
        }

        MinecraftServer server = event.getServer();
        if (!server.isDedicatedServer()) {
            return;
        }
        int port = server.getPort();
        ZstdNetConfig config = ZstdNetConfig.defaults(
            new HostPort("0.0.0.0", port),
            new HostPort("same-port", port)
        );
        Path dictionaryPath = FMLPaths.CONFIGDIR.get().resolve("zstdnet").resolve("dictionary.zdict");
        ZstdDictionaryStore dictionaryStore = new ZstdDictionaryStore(dictionaryPath, proxyLogger);
        dictionaryStore.load();

        ZstdDictionaryTrainer trainer = new ZstdDictionaryTrainer(dictionaryStore, proxyLogger);
        try {
            NeoForgeSamePortZstdInjector next = new NeoForgeSamePortZstdInjector(
                server,
                config,
                proxyLogger,
                dictionaryStore,
                trainer
            );
            next.inject();
            injector = next;
            dictionaryTrainer = trainer;
        } catch (Exception e) {
            trainer.close();
            LOGGER.error("Could not inject ZstdNet into the Minecraft Netty listener", e);
        }
    }

    private synchronized void onServerStopping(ServerStoppingEvent event) {
        if (injector != null) {
            injector.close();
            injector = null;
        }
        if (dictionaryTrainer != null) {
            dictionaryTrainer.close();
            dictionaryTrainer = null;
        }
    }

    private record Slf4jProxyLogger(Logger logger) implements ProxyLogger {
        @Override
        public void info(String message) {
            logger.info(message);
        }

        @Override
        public void warn(String message) {
            logger.warn(message);
        }

        @Override
        public void error(String message) {
            logger.error(message);
        }
    }
}
