package mys.zstdnet.reborn.core.dictionary;

import mys.zstdnet.reborn.core.proxy.ProxyLogger;
import com.github.luben.zstd.Zstd;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ZstdDictionaryTrainer implements AutoCloseable {
    public static final Duration DEFAULT_DURATION = Duration.ofMinutes(10);
    private final Object lock = new Object();
    private final ZstdDictionaryStore store;
    private final ProxyLogger logger;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "zstdnet-dictionary-training");
        thread.setDaemon(true);
        return thread;
    });
    private Session session;
    private boolean closed;
    private String result = "idle";

    public ZstdDictionaryTrainer(ZstdDictionaryStore store, ProxyLogger logger) {
        this.store = store;
        this.logger = logger;
    }

    public boolean start(Duration duration, int compressionLevel) {
        Duration selected = duration == null ? DEFAULT_DURATION : duration;
        if (selected.isZero() || selected.isNegative() || selected.compareTo(Duration.ofDays(1)) > 0) return false;
        synchronized (lock) {
            if (closed || session != null) return false;
            Session next = new Session(System.currentTimeMillis() + selected.toMillis(), compressionLevel);
            session = next;
            result = "collecting";
            next.task = executor.schedule(() -> finish(next), selected.toMillis(), TimeUnit.MILLISECONDS);
            logger.info("Dictionary training started for " + selected.toSeconds() + " seconds");
            return true;
        }
    }

    public boolean stopAndFinalize() {
        synchronized (lock) {
            if (closed || session == null || session.finalizing) return false;
            Session current = session;
            current.task.cancel(false);
            current.finalizing = true;
            executor.execute(() -> train(current));
            return true;
        }
    }

    public void capture(byte[] raw) {
        if (raw == null || raw.length == 0) return;
        synchronized (lock) {
            if (closed || session == null || session.finalizing || session.samples.size() >= 8192) return;
            int length = Math.min(raw.length, Math.min(64 * 1024, 32 * 1024 * 1024 - session.totalBytes));
            if (length <= 0) return;
            session.samples.add(Arrays.copyOf(raw, length));
            session.totalBytes += length;
        }
    }

    public Status status() {
        synchronized (lock) {
            return session == null ? new Status(false, false, 0, 0, 0, result)
                : new Status(true, session.finalizing, Math.max(0, session.endsAt - System.currentTimeMillis()),
                    session.samples.size(), session.totalBytes, result);
        }
    }

    public void abort() {
        synchronized (lock) {
            if (session != null) {
                session.task.cancel(false);
                session = null;
                result = "cancelled";
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            abort();
            executor.shutdownNow();
        }
    }

    private void finish(Session expected) {
        synchronized (lock) {
            if (closed || session != expected || expected.finalizing) return;
            expected.finalizing = true;
        }
        train(expected);
    }

    private void train(Session expected) {
        try {
            if (expected.samples.size() <= 10 || expected.totalBytes < 4096) {
                throw new IllegalStateException("not enough traffic samples; connect players and try again");
            }
            int size = Math.max(4096, Math.min(ZstdDictionary.MAX_BYTES, expected.totalBytes / 100));
            byte[] buffer = new byte[size];
            long trained = Zstd.trainFromBuffer(expected.samples.toArray(byte[][]::new), buffer, false, expected.level);
            if (Zstd.isError(trained) || trained <= 0 || trained > buffer.length) {
                throw new IllegalStateException(Zstd.getErrorName(trained));
            }
            synchronized (lock) {
                // Native training cannot be interrupted safely. Cancel its publication on stop/import.
                if (closed || session != expected) return;
                ZstdDictionary dictionary = store.save(Arrays.copyOf(buffer, (int) trained));
                result = "saved dictionary " + Long.toUnsignedString(dictionary.id());
                session = null;
                logger.info(result + "; new connections will synchronize it");
            }
        } catch (Exception e) {
            synchronized (lock) {
                if (closed || session != expected) return;
                result = "failed: " + e.getMessage();
                session = null;
                logger.warn("Dictionary training " + result);
            }
        }
    }

    public record Status(boolean training, boolean finalizing, long remainingMillis,
                         int sampleCount, int sampleBytes, String result) {}
    private static final class Session {
        final long endsAt;
        final int level;
        final List<byte[]> samples = new ArrayList<>();
        int totalBytes;
        boolean finalizing;
        ScheduledFuture<?> task;
        Session(long endsAt, int level) { this.endsAt = endsAt; this.level = level; }
    }
}
