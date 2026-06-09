package cn.tohsaka.factory.zstdnet26.core.proxy;

import cn.tohsaka.factory.zstdnet26.core.limit.TokenBucketLimiter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

final class RateLimitedOutputStream extends OutputStream {
    private static final int CHUNK_SIZE = 16 * 1024;

    private final OutputStream delegate;
    private final TokenBucketLimiter perConnection;
    private final TokenBucketLimiter global;

    RateLimitedOutputStream(OutputStream delegate, TokenBucketLimiter perConnection, TokenBucketLimiter global) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.perConnection = perConnection;
        this.global = global;
    }

    @Override
    public void write(int b) throws IOException {
        throttle(1);
        delegate.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        int written = 0;
        while (written < len) {
            int chunk = Math.min(CHUNK_SIZE, len - written);
            throttle(chunk);
            delegate.write(b, off + written, chunk);
            written += chunk;
        }
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    private void throttle(int bytes) {
        if (perConnection != null) {
            perConnection.waitBytes(bytes);
        }
        if (global != null) {
            global.waitBytes(bytes);
        }
    }
}
