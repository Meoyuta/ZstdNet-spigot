package mys.zstdnet.reborn.neoforge.v1_21_1;

import mys.zstdnet.reborn.client.ZstdNetClient;
import mys.zstdnet.reborn.core.HostPort;
import mys.zstdnet.reborn.core.ZstdNetConfig;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryStore;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryTrainer;
import mys.zstdnet.reborn.core.benchmark.CompressionBenchmark;
import mys.zstdnet.reborn.core.utils.ZstdNetLogger;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

@Mod(ZstdNet.MOD_ID)
public final class ZstdNet {
    public static final String MOD_ID = "zstdnet";

    private static final Logger LOGGER = LoggerFactory.getLogger("ZstdNet");

    private final ZstdNetLogger logger = new Slf4jLogger(LOGGER);
    private SamePortZstdInjector injector;
    private ZstdDictionaryTrainer dictionaryTrainer;
    private CompressionBenchmark benchmark;
    private volatile net.minecraft.server.level.ServerPlayer pendingBenchmarkRequester;
    private final AtomicInteger compressionLevel = new AtomicInteger(9);
    // One store owns the current immutable dictionary for the lifetime of this mod instance.
    private final ZstdDictionaryStore dictionaryStore = new ZstdDictionaryStore(
        FMLPaths.CONFIGDIR.get().resolve("zstdnet").resolve("dict").resolve("dictionary.zdict"), logger);
    private boolean accepting;

    public ZstdNet(IEventBus modEventBus) {
        modEventBus.addListener(this::registerPayloads);
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

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(BenchmarkInfoPayload.TYPE, BenchmarkInfoPayload.STREAM_CODEC,
             (payload, context) -> BenchmarkInfoClientState.receive(payload));
        event.registrar("1").playToClient(ManagementStatusPayload.TYPE, ManagementStatusPayload.STREAM_CODEC,
            (payload, context) -> ManagementStatusClientState.receive(payload));
        event.registrar("1").playToClient(DictionaryStatusPayload.TYPE, DictionaryStatusPayload.STREAM_CODEC,
            (payload, context) -> DictionaryStatusClientState.receive(payload));
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
            if (benchmark == null) {
                benchmark = new CompressionBenchmark(
                    FMLPaths.CONFIGDIR.get().resolve("zstdnet").resolve("server.properties"),
                    logger,
                    () -> injector == null ? 0 : injector.snapshot().connections(),
                    compressionLevel::get,
                    compressionLevel::set,
                    dictionaryStore::dictionary
                );
                benchmark.setCompletionListener(this::onBenchmarkComplete);
            }
            var next = new SamePortZstdInjector(
                server,
                config,
                logger,
                dictionaryStore,
                trainer,
                benchmark,
                compressionLevel::get
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
        if (benchmark != null) {
            benchmark.close();
            benchmark = null;
        }
        pendingBenchmarkRequester = null;
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

    synchronized void tickBenchmark() {
        if (benchmark != null) benchmark.tick();
    }

    synchronized boolean startBenchmark(net.minecraft.server.level.ServerPlayer requester) {
        if (benchmark == null) return false;
        var started = benchmark.start();
        if (started && requester != null) pendingBenchmarkRequester = requester;
        return started;
    }

    private void onBenchmarkComplete(CompressionBenchmark.Result result) {
        if (!"complete".equals(result.state())) return;
        final var requester = pendingBenchmarkRequester;
        pendingBenchmarkRequester = null;
        if (requester != null && requester.getServer() != null) {
            requester.getServer().execute(() -> sendBenchmarkInfo(requester));
        }
    }

    synchronized void sendBenchmarkInfo(net.minecraft.server.level.ServerPlayer player) {
        if (benchmark == null || player == null || !player.connection.isAcceptingMessages()) return;
        var result = benchmark.result();
        player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
            new BenchmarkInfoPayload(result.state(), result.level(), result.automatic(),
                result.compressionPercent(), result.codecMillis(), result.estimatedMillis(),
                result.samples(), result.completedAt(), result.intervalMinutes())));
    }

    synchronized void sendManagementStatus(net.minecraft.server.level.ServerPlayer player) {
        if (player == null || !player.connection.isAcceptingMessages()) return;
        var stats = snapshot();
        var selected = dictionaryStore.dictionary();
        var dictionary = selected == null ? "none" : selectedDictionaryDescription();
        player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
            new ManagementStatusPayload(isRunning() ? "running" : "stopped", player.getServer().getPort(),
                stats.wireUpBytes(), stats.rawUpBytes(), stats.wireDownBytes(), stats.rawDownBytes(),
                stats.ratioPercent(), stats.connections(), dictionary,
                dictionaryConnections(), selectedDictionaryConnections())));
    }

    synchronized void sendDictionaryStatus(net.minecraft.server.level.ServerPlayer player, String mode) {
        if (player == null || !player.connection.isAcceptingMessages() || dictionaryTrainer == null) return;
        var status = dictionaryTrainer.status();
        var selected = dictionaryStore.dictionary();
        var description = selected == null ? "none" : selectedDictionaryDescription();
        var path = dictionaryStore.selectedPath() == null ? "none" : dictionaryStore.selectedPath().toString();
        var available = java.util.List.<String>of();
        try {
            available = dictionaryStore.available();
        } catch (IOException e) {
            logger.warn("Could not enumerate dictionaries: " + e.getMessage());
        }
        player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
            new DictionaryStatusPayload(mode, description, path, dictionaryStatusText(status),
                status.sampleCount(), status.sampleBytes(), Math.max(0, status.remainingMillis() / 1000),
                available)));
    }

    private String selectedDictionaryDescription() {
        var selected = dictionaryStore.dictionary();
        var path = dictionaryStore.selectedPath();
        var name = path == null ? "unknown" : path.getFileName().toString();
        if (name.endsWith(".zdict")) name = name.substring(0, name.length() - 6);
        return name + " (id:" + Long.toUnsignedString(selected.id()) + ", " + selected.size() + " bytes)";
    }

    private static String dictionaryStatusText(ZstdDictionaryTrainer.Status status) {
        if (status.finalizing()) return "training";
        if (status.training()) return "collecting";
        return status.result();
    }

    synchronized CompressionBenchmark.Result benchmarkResult() {
        return benchmark == null ? CompressionBenchmark.Result.skipped("not_running", true,
            compressionLevel.get(), 30) : benchmark.result();
    }

    synchronized boolean benchmarkRunning() {
        return benchmark != null && benchmark.isRunning();
    }

    synchronized int compressionLevel() {
        return compressionLevel.get();
    }

    synchronized void setTemporaryCompressionLevel(int level) {
        if (benchmark == null) throw new IllegalStateException("compression benchmark is not initialized");
        benchmark.setTemporaryLevel(level);
    }

    synchronized void setAutomaticCompression() throws IOException {
        if (benchmark == null) throw new IllegalStateException("compression benchmark is not initialized");
        benchmark.setAutomatic();
    }

    synchronized void setBenchmarkInterval(int minutes) throws IOException {
        if (benchmark == null) throw new IllegalStateException("compression benchmark is not initialized");
        benchmark.setInterval(minutes);
    }

    synchronized int benchmarkInterval() {
        return benchmark == null ? 30 : benchmark.intervalMinutes();
    }

    synchronized boolean automaticCompression() {
        return benchmark == null || benchmark.automatic();
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
