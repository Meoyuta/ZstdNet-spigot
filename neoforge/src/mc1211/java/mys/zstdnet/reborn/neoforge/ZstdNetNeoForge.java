package mys.zstdnet.reborn.neoforge;

import mys.zstdnet.reborn.client.ZstdNetClient;
import mys.zstdnet.reborn.core.HostPort;
import mys.zstdnet.reborn.core.ZstdNetConfig;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryStore;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryTrainer;
import mys.zstdnet.reborn.core.proxy.ProxyLogger;
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
    private ZstdDictionaryStore dictionaryStore;

    public ZstdNetNeoForge() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ZstdNetClient.init(FMLPaths.CONFIGDIR.get(), proxyLogger);
            ZstdNetClient.setDictionaryDownloadListenerFactory(DictionaryDownloadScreen::listener);
        }

        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent event) ->
            NeoForgeDictionaryCommand.register(event, () -> dictionaryStore, () -> dictionaryTrainer));
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
        dictionaryStore = new ZstdDictionaryStore(dictionaryPath, proxyLogger);
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
        if (dictionaryTrainer != null) {
            dictionaryTrainer.close();
        }
        if (injector != null) {
            injector.close();
            injector = null;
        }
        if (dictionaryTrainer != null) {
            dictionaryTrainer = null;
        }
        dictionaryStore = null;
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
