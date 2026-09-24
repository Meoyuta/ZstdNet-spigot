package mys.zstdnet.reborn.neoforge;

import mys.zstdnet.reborn.client.ZstdNetClient;
import mys.zstdnet.reborn.core.HostPort;
import mys.zstdnet.reborn.core.ZstdNetConfig;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryStore;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryTrainer;
import mys.zstdnet.reborn.core.utils.ZstdNetLogger;
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

@Mod(ZstdNet.MOD_ID)
public final class ZstdNet {
    public static final String MOD_ID = "zstdnet";

    private static final Logger LOGGER = LoggerFactory.getLogger("ZstdNet");

    private final ZstdNetLogger logger = new Slf4jLogger(LOGGER);
    private SamePortZstdInjector injector;
    private ZstdDictionaryTrainer dictionaryTrainer;
    // One store owns the current immutable dictionary for the lifetime of this mod instance.
    private final ZstdDictionaryStore dictionaryStore = new ZstdDictionaryStore(
        FMLPaths.CONFIGDIR.get().resolve("zstdnet").resolve("dictionary.zdict"), logger);
    private boolean accepting;

    public ZstdNet() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ZstdNetClient.init(FMLPaths.CONFIGDIR.get(), logger);
            ZstdNetClient.setDictionaryDownloadListenerFactory(DictionaryDownloadScreen::listener);
        }

        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) ->
            ZstdCommands.tick(event.getServer(), this));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                ZstdCommands.prompt(player, this);
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent event) ->
            ZstdCommands.register(event, this));
    }

    private synchronized void onServerStarted(ServerStartedEvent event) {
        // Compression is enabled automatically for every dedicated-server session.
        if (event.getServer().isDedicatedServer()) start(event.getServer());
    }

    synchronized boolean start(MinecraftServer server) {
        if (!server.isDedicatedServer()) return false;
        if (injector != null) {
            if (!accepting) {
                injector.inject();
                accepting = true;
            }
            return true;
        }
        var port = server.getPort();
        var config = ZstdNetConfig.defaults(
            new HostPort("0.0.0.0", port),
            new HostPort("same-port", port)
        );
        try {
            dictionaryStore.enableNaming();
        } catch (java.io.IOException e) {
            LOGGER.error("Could not restore dictionary naming records", e);
            return false;
        }
        dictionaryStore.loadSelected();

        var trainer = new ZstdDictionaryTrainer(dictionaryStore, logger);
        try {
            var next = new SamePortZstdInjector(
                server,
                config,
                logger,
                dictionaryStore,
                trainer
            );
            next.inject();
            injector = next;
            dictionaryTrainer = trainer;
            accepting = true;
            return true;
        } catch (Exception e) {
            trainer.close();
            LOGGER.error("Could not inject ZstdNet into the Minecraft Netty listener", e);
            return false;
        }
    }

    private synchronized void onServerStopping(ServerStoppingEvent event) {
        if (dictionaryTrainer != null) {
            dictionaryStore.beginShutdown();
            dictionaryTrainer.finishAndClose();
        }
        if (injector != null) {
            injector.close();
            injector = null;
        }
        if (dictionaryTrainer != null) {
            dictionaryTrainer = null;
        }
        accepting = false;
    }

    synchronized void stop() {
        if (dictionaryTrainer != null) dictionaryTrainer.abort();
        if (injector != null && accepting) {
            injector.close();
            accepting = false;
        }
    }

    synchronized boolean reload(MinecraftServer server) {
        if (!start(server)) return false;
        dictionaryTrainer.abort();
        dictionaryStore.loadSelected();
        return true;
    }

    synchronized boolean isRunning() {
        return accepting;
    }

    synchronized int dictionaryConnections() {
        return injector == null ? 0 : injector.dictionaryConnections();
    }

    synchronized int selectedDictionaryConnections() {
        var dictionary = dictionaryStore.dictionary();
        return injector == null || dictionary == null ? 0 : injector.dictionaryConnections(dictionary.id());
    }

    synchronized mys.zstdnet.reborn.core.stats.TrafficStats.Snapshot snapshot() {
        return injector == null ? new mys.zstdnet.reborn.core.stats.TrafficStats().snapshot() : injector.snapshot();
    }

    synchronized ZstdDictionaryStore dictionaryStore() {
        return dictionaryStore;
    }

    synchronized ZstdDictionaryTrainer dictionaryTrainer() {
        return dictionaryTrainer;
    }

    void commandFailed(String action, Exception error) {
        LOGGER.error("ZstdNet command failed: " + action, error);
    }

    private record Slf4jLogger(Logger logger) implements ZstdNetLogger {
        @Override
        public void debug(String message) {
            logger.debug(message);
        }

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
