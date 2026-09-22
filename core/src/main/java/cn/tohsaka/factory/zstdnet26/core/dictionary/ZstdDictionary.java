package cn.tohsaka.factory.zstdnet26.core.dictionary;

import com.github.luben.zstd.Zstd;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public final class ZstdDictionary {
    public static final int MIN_BYTES = 256;
    public static final int MAX_BYTES = 112 * 1024;

    private final byte[] bytes;
    private final long id;

    private ZstdDictionary(byte[] bytes, long id) {
        this.bytes = bytes;
        this.id = id;
    }

    public static ZstdDictionary fromBytes(byte[] source) throws IOException {
        Objects.requireNonNull(source, "source");
        if (source.length < MIN_BYTES || source.length > MAX_BYTES) {
            throw new IOException("dictionary size must be between " + MIN_BYTES + " and " + MAX_BYTES + " bytes");
        }

        byte[] bytes = Arrays.copyOf(source, source.length);
        long id;
        try {
            id = Zstd.getDictIdFromDict(bytes);
        } catch (RuntimeException e) {
            throw new IOException("could not read ZSTD dictionary id", e);
        }
        if (Zstd.isError(id) || id == 0L) {
            throw new IOException("invalid ZSTD dictionary");
        }
        return new ZstdDictionary(bytes, id);
    }

    public long id() {
        return id;
    }

    public int size() {
        return bytes.length;
    }

    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    public byte[] compress(byte[] raw, int level) {
        return Zstd.compressUsingDict(raw, bytes, level);
    }

    public byte[] decompress(byte[] compressed, int rawLength) {
        return Zstd.decompress(compressed, bytes, rawLength);
    }
}
