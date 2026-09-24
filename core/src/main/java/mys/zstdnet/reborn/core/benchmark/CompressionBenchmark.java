package mys.zstdnet.reborn.core.benchmark;

import mys.zstdnet.reborn.core.dictionary.ZstdDictionary;
import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;
import mys.zstdnet.reborn.core.utils.ZstdNetLogger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.Consumer;

public final class CompressionBenchmark implements AutoCloseable {
    private static final int SAMPLE_LIMIT = 16 * 1024 * 1024;
    private static final int MAX_SAMPLE_BYTES = 4 * 1024;
    private static final int SAMPLE_COUNT_LIMIT = 4096;
    private static final int MINIMUM_SAMPLES = 4096;
    private static final double LATENCY_BUDGET_MILLIS = 10.0;
    private final Path configPath;
    private final ZstdNetLogger logger;
    private final IntSupplier connectionCount;
    private final IntSupplier currentLevel;
    private final IntConsumer levelSetter;
    private final Supplier<ZstdDictionary> dictionarySupplier;
    private volatile Consumer<Result> completionListener = ignored -> {};
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "zstdnet-compression-benchmark");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final ArrayDeque<byte[]> samples = new ArrayDeque<>();
    private final Properties config = new Properties();
    private int sampledBytes;
    private int intervalMinutes = 30;
    private boolean automatic = true;
    private volatile Result lastResult;
    private volatile long nextRunAt;

    public CompressionBenchmark(Path configPath, ZstdNetLogger logger, IntSupplier connectionCount,
                                IntSupplier currentLevel, IntConsumer levelSetter,
                                Supplier<ZstdDictionary> dictionarySupplier) throws IOException {
        this.configPath = configPath;
        this.logger = logger;
        this.connectionCount = connectionCount;
        this.currentLevel = currentLevel;
        this.levelSetter = levelSetter;
        this.dictionarySupplier = dictionarySupplier;
        loadConfig();
        nextRunAt = System.currentTimeMillis() + intervalMillis();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long byteCount(List<byte[]> values) {
        return values.stream().mapToLong(value -> value.length).sum();
    }

    public synchronized void capture(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > ZstdFrameCodec.MAX_FRAME_BYTES) return;
        var copy = raw.length > MAX_SAMPLE_BYTES
                ? java.util.Arrays.copyOf(raw, MAX_SAMPLE_BYTES)
                : raw.clone();
        samples.addLast(copy);
        sampledBytes += copy.length;
        while (samples.size() > SAMPLE_COUNT_LIMIT || sampledBytes > SAMPLE_LIMIT) {
            sampledBytes -= samples.removeFirst().length;
        }
    }

    public void tick() {
        if (System.currentTimeMillis() >= nextRunAt) request(true);
    }

    public boolean start() {
        return request(false);
    }

    public void setCompletionListener(Consumer<Result> listener) {
        completionListener = listener == null ? ignored -> {} : listener;
    }

    private boolean request(boolean scheduled) {
        var activeConnections = connectionCount.getAsInt();
        List<byte[]> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(samples);
        }
        var trigger = scheduled ? "scheduled" : "manual";
        if (!running.compareAndSet(false, true)) {
            logger.info("Compression benchmark skipped (%s): another benchmark is running".formatted(trigger));
            if (scheduled) scheduleNext();
            if (!scheduled) return false;
            return false;
        }
        if (activeConnections <= 0) {
            running.set(false);
            logger.info("Compression benchmark skipped (%s): no active ZstdNet connections".formatted(trigger));
            if (!scheduled)
                lastResult = Result.skipped("no_connections", automatic, currentLevel.getAsInt(), intervalMinutes);
            scheduleNext();
            return false;
        }
        if (snapshot.size() < MINIMUM_SAMPLES) {
            running.set(false);
            lastResult = Result.waiting(automatic, currentLevel.getAsInt(), snapshot.size(), intervalMinutes);
            logger.info("Compression benchmark waiting (%s): %d real packet samples available; need at least %d"
                    .formatted(trigger, snapshot.size(), MINIMUM_SAMPLES));
            nextRunAt = System.currentTimeMillis() + 1_000L;
            return true;
        }

        logger.info("Compression benchmark started (%s): %d active connection(s), %d real packet samples, %d bytes, dictionary=%s"
                .formatted(trigger, activeConnections, snapshot.size(), byteCount(snapshot), dictionarySupplier.get() != null));
        var useAutomatic = automatic;
        var dictionary = dictionarySupplier.get();
        executor.execute(() -> {
            try {
                var result = benchmark(snapshot, dictionary, useAutomatic);
                if (useAutomatic) levelSetter.accept(result.level());
                lastResult = result;
                completionListener.accept(result);
                logger.info("Compression benchmark completed: level=%d, mode=%s, compression=%.2f%%, encode+decode=%.3f ms, estimated total=%.3f ms, samples=%d"
                        .formatted(result.level(), result.automatic() ? "automatic" : "manual", result.compressionPercent(),
                                result.codecMillis(), result.estimatedMillis(), result.samples()));
            } catch (Exception e) {
                lastResult = Result.skipped("failed", useAutomatic, currentLevel.getAsInt(), intervalMinutes);
                completionListener.accept(lastResult);
                logger.error("Compression benchmark failed: " + e);
            } finally {
                running.set(false);
                scheduleNext();
            }
        });
        return true;
    }

    private Result benchmark(List<byte[]> corpus, ZstdDictionary dictionary, boolean applyResult) throws IOException {
        var candidates = new ArrayList<Result>(22);
        var current = currentLevel.getAsInt();
        for (var level = 1; level <= 22; level++) {
            long encodedBytes = 0;
            long codecNanos = 0;
            for (var raw : corpus) {
                var encodeStart = System.nanoTime();
                var frame = ZstdFrameCodec.compressFrame(raw, level, dictionary);
                var encodeNanos = System.nanoTime() - encodeStart;
                var decodeStart = System.nanoTime();
                var decoded = ZstdFrameCodec.readFrame(new ByteArrayInputStream(frame), dictionary);
                var decodeNanos = System.nanoTime() - decodeStart;
                if (decoded.length != raw.length) throw new IOException("benchmark round-trip length mismatch");
                encodedBytes += frame.length;
                codecNanos += encodeNanos + decodeNanos;
            }
            var rawBytes = byteCount(corpus);
            var compressionPercent = 100.0 * encodedBytes / rawBytes;
            var codecMillis = codecNanos / corpus.size() / 1_000_000.0;
            var averageEncodedBytes = (double) encodedBytes / corpus.size();
            var networkMillis = averageEncodedBytes * 8.0 / 100_000_000.0 * 1_000.0;
            var estimatedMillis = codecMillis + networkMillis;
            var candidate = new Result("complete", level, applyResult, compressionPercent,
                    codecMillis, estimatedMillis, corpus.size(), System.currentTimeMillis(), intervalMinutes);
            candidates.add(candidate);
        }
        var fastestLatency = candidates.stream().mapToDouble(Result::estimatedMillis).min().orElseThrow();
        var best = candidates.stream()
                .filter(candidate -> candidate.estimatedMillis() < fastestLatency + LATENCY_BUDGET_MILLIS)
                .min((left, right) -> Double.compare(left.compressionPercent(), right.compressionPercent()))
                .orElseThrow();
        if (!applyResult) return new Result(best.state(), current, false, best.compressionPercent(),
                best.codecMillis(), best.estimatedMillis(), corpus.size(), System.currentTimeMillis(), intervalMinutes);
        return best;
    }

    public synchronized void setTemporaryLevel(int level) {
        levelSetter.accept(level);
        logger.info("Compression level temporarily set to " + level
                + " (automatic benchmarking remains enabled)");
    }

    public synchronized void setAutomatic() throws IOException {
        automatic = true;
        saveConfig();
        logger.info("Compression level switched to automatic benchmarking; current level is "
                + currentLevel.getAsInt());
    }

    public synchronized void setInterval(int minutes) throws IOException {
        intervalMinutes = minutes;
        saveConfig();
        scheduleNext();
        logger.info("Compression benchmark interval set to " + minutes + " minutes");
    }

    public synchronized int intervalMinutes() {
        return intervalMinutes;
    }

    public synchronized boolean automatic() {
        return automatic;
    }

    public boolean isRunning() {
        return running.get();
    }

    public synchronized int sampleCount() {
        return samples.size();
    }

    public Result result() {
        var result = lastResult;
        return result == null ? Result.skipped("waiting", automatic, currentLevel.getAsInt(), intervalMinutes) : result;
    }

    private synchronized void scheduleNext() {
        nextRunAt = System.currentTimeMillis() + intervalMillis();
    }

    private long intervalMillis() {
        return intervalMinutes * 60_000L;
    }

    private void loadConfig() throws IOException {
        Files.createDirectories(configPath.getParent());
        if (Files.isRegularFile(configPath)) {
            try (var input = Files.newInputStream(configPath)) {
                config.load(input);
            }
        }
        intervalMinutes = Math.clamp(parseInt(config.getProperty("benchmark-interval-minutes"), 30), 1, 10080);
        var configuredLevel = config.getProperty("compression-level", "auto").trim();
        automatic = true;
        if (!configuredLevel.equalsIgnoreCase("auto")) {
            levelSetter.accept(Math.clamp(parseInt(configuredLevel, currentLevel.getAsInt()), 1, 22));
        }
        config.setProperty("benchmark-interval-minutes", Integer.toString(intervalMinutes));
        config.setProperty("compression-level", "auto");
        saveConfig();
    }

    private synchronized void saveConfig() throws IOException {
        config.setProperty("benchmark-interval-minutes", Integer.toString(intervalMinutes));
        config.setProperty("compression-level", "auto");
        try (var output = Files.newOutputStream(configPath)) {
            config.store(output, "ZstdNet server compression settings");
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    public record Result(String state, int level, boolean automatic, double compressionPercent,
                         double codecMillis, double estimatedMillis, int samples, long completedAt,
                         int intervalMinutes) {
        public static Result skipped(String state, boolean automatic, int level, int intervalMinutes) {
            return new Result(state, level, automatic, Double.NaN, Double.NaN, Double.NaN,
                    0, 0, intervalMinutes);
        }

        public static Result running(boolean automatic, int level, int intervalMinutes) {
            return new Result("running", level, automatic, Double.NaN, Double.NaN, Double.NaN,
                    0, 0, intervalMinutes);
        }

        public static Result waiting(boolean automatic, int level, int samples, int intervalMinutes) {
            return new Result("waiting", level, automatic, Double.NaN, Double.NaN, Double.NaN,
                    samples, 0, intervalMinutes);
        }
    }
}
