package cn.tohsaka.factory.zstdnet26.core.dictionary;

import cn.tohsaka.factory.zstdnet26.core.proxy.ProxyLogger;
import com.github.luben.zstd.Zstd;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class ZstdDictionaryTrainer implements AutoCloseable {
    public static final Duration DEFAULT_DURATION = Duration.ofMinutes(10);

    private static final int MAX_SAMPLE_BYTES = 64 * 1024;
    private static final int MAX_TOTAL_SAMPLE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_SAMPLE_COUNT = 8192;

    private final Object lock = new Object();
    private final ZstdDictionaryStore store;
    private final ProxyLogger logger;
    private final ScheduledExecutorService executor;
    private Session session;

    public ZstdDictionaryTrainer(ZstdDictionaryStore store, ProxyLogger logger) {
        this.store = Objects.requireNonNull(store, "store");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.executor = Executors.newSingleThreadScheduledExecutor(new TrainingThreadFactory());
    }

    public boolean start(Duration duration, int compressionLevel) {
        Duration safeDuration = duration == null ? DEFAULT_DURATION : duration;
        if (safeDuration.isNegative() || safeDuration.isZero()) {
            return false;
        }
        synchronized (lock) {
            if (session != null) {
                return false;
            }
            Session next = new Session(System.currentTimeMillis() + safeDuration.toMillis(), compressionLevel);
            next.finishTask = executor.schedule(() -> finish(next), safeDuration.toMillis(), TimeUnit.MILLISECONDS);
            session = next;
            logger.info("started ZstdNet dictionary training for " + safeDuration.toSeconds() + " seconds");
            return true;
        }
    }

    public boolean stopAndFinalize() {
        Session current;
        synchronized (lock) {
            current = session;
            if (current == null) {
                return false;
            }
            session = null;
            current.finishTask.cancel(false);
        }
        executor.execute(() -> train(current));
        return true;
    }

    public void capture(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return;
        }
        synchronized (lock) {
            if (session == null || session.samples.size() >= MAX_SAMPLE_COUNT || session.totalBytes >= MAX_TOTAL_SAMPLE_BYTES) {
                return;
            }
            int length = Math.min(raw.length, Math.min(MAX_SAMPLE_BYTES, MAX_TOTAL_SAMPLE_BYTES - session.totalBytes));
            if (length <= 0) {
                return;
            }
            session.samples.add(Arrays.copyOf(raw, length));
            session.totalBytes += length;
        }
    }

    public Status status() {
        synchronized (lock) {
            if (session == null) {
                return new Status(false, 0L, 0, 0);
            }
            return new Status(true, Math.max(0L, session.endsAtMillis - System.currentTimeMillis()), session.samples.size(), session.totalBytes);
        }
    }

    public void abort() {
        synchronized (lock) {
            if (session == null) {
                return;
            }
            session.finishTask.cancel(false);
            session = null;
        }
        logger.info("aborted ZstdNet dictionary training");
    }

    @Override
    public void close() {
        abort();
        executor.shutdownNow();
    }

    private void finish(Session expected) {
        synchronized (lock) {
            if (session != expected) {
                return;
            }
            session = null;
        }
        train(expected);
    }

    private void train(Session finished) {
        List<byte[]> samples = new ArrayList<>(finished.samples);
        if (samples.size() <= 10 || finished.totalBytes < ZstdDictionary.MIN_BYTES) {
            logger.warn("ZstdNet dictionary training ended without enough samples");
            return;
        }

        int dictionarySize = Math.max(4 * 1024, Math.min(ZstdDictionary.MAX_BYTES, finished.totalBytes / 100));
        byte[] buffer = new byte[dictionarySize];
        try {
            long trained = Zstd.trainFromBuffer(samples.toArray(byte[][]::new), buffer, false, finished.compressionLevel);
            if (Zstd.isError(trained) || trained <= 0L || trained > buffer.length) {
                logger.warn("ZstdNet dictionary training failed: " + Zstd.getErrorName(trained));
                return;
            }
            ZstdDictionary dictionary = store.save(Arrays.copyOf(buffer, (int) trained));
            logger.info("completed ZstdNet dictionary training id=" + Long.toUnsignedString(dictionary.id())
                + " samples=" + samples.size() + " bytes=" + finished.totalBytes);
        } catch (Exception e) {
            logger.warn("ZstdNet dictionary training failed: " + e);
        }
    }

    public record Status(boolean training, long remainingMillis, int sampleCount, int sampleBytes) {
    }

    private static final class Session {
        private final long endsAtMillis;
        private final int compressionLevel;
        private final List<byte[]> samples = new ArrayList<>();
        private int totalBytes;
        private ScheduledFuture<?> finishTask;

        private Session(long endsAtMillis, int compressionLevel) {
            this.endsAtMillis = endsAtMillis;
            this.compressionLevel = compressionLevel;
        }
    }

    private static final class TrainingThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "zstdnet-dictionary-training");
            thread.setDaemon(true);
            return thread;
        }
    }
}
