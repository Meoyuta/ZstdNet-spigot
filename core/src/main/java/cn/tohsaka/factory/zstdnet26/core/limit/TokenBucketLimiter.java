package cn.tohsaka.factory.zstdnet26.core.limit;

public final class TokenBucketLimiter {
    private final long rateBytesPerSecond;
    private final long burstBytes;
    private double tokens;
    private long lastNanos;

    private TokenBucketLimiter(long rateBytesPerSecond, long burstBytes) {
        this.rateBytesPerSecond = rateBytesPerSecond;
        this.burstBytes = Math.max(1L, burstBytes);
        this.tokens = this.burstBytes;
        this.lastNanos = System.nanoTime();
    }

    public static TokenBucketLimiter create(long rateBytesPerSecond, long burstBytes) {
        if (rateBytesPerSecond <= 0L) {
            return null;
        }
        return new TokenBucketLimiter(rateBytesPerSecond, burstBytes);
    }

    public synchronized void waitBytes(int bytes) {
        if (bytes <= 0 || rateBytesPerSecond <= 0L) {
            return;
        }
        refill();
        while (tokens < bytes) {
            double missing = bytes - tokens;
            long sleepMillis = Math.max(1L, (long) Math.ceil(missing * 1000.0D / rateBytesPerSecond));
            try {
                wait(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            refill();
        }
        tokens -= bytes;
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastNanos;
        if (elapsed <= 0L) {
            return;
        }
        tokens = Math.min(burstBytes, tokens + (elapsed / 1_000_000_000.0D) * rateBytesPerSecond);
        lastNanos = now;
    }
}
