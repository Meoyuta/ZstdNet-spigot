package mys.zstdnet.reborn.core.dictionary;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.util.concurrent.ConcurrentHashMap;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public final class ZstdDictionary {
    public static final int MIN_BYTES = 256;
    public static final int MAX_BYTES = 128 * 1024;

    private final byte[] bytes;
    private final long id;
    private static final Cleaner CLEANER = Cleaner.create();
    private final Prepared prepared;

    private ZstdDictionary(byte[] bytes, long id) {
        this.bytes = bytes;
        this.id = id;
        this.prepared = new Prepared(bytes);
        CLEANER.register(this, prepared);
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
        // Reading the ID alone does not validate the entropy tables.
        try {
            byte[] probe = new byte[1024];
            byte[] compressed = Zstd.compressUsingDict(probe, bytes, 1);
            if (!Arrays.equals(probe, Zstd.decompress(compressed, bytes, probe.length))) {
                throw new IOException("dictionary round-trip validation failed");
            }
        } catch (RuntimeException e) {
            throw new IOException("invalid ZSTD dictionary tables", e);
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
        try {
            return Zstd.compress(raw, prepared.compressors.computeIfAbsent(
                Math.max(1, Math.min(22, level)), n -> new ZstdDictCompress(bytes, n)));
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public byte[] decompress(byte[] compressed, int rawLength) {
        try {
            return Zstd.decompress(compressed, prepared.decompressor, rawLength);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    // Immutable native dictionaries are shared; contexts remain local to each operation.
    // Cleanup happens only once no store or connection references this dictionary.
    private static final class Prepared implements Runnable {
        final ConcurrentHashMap<Integer, ZstdDictCompress> compressors = new ConcurrentHashMap<>();
        final ZstdDictDecompress decompressor;
        Prepared(byte[] bytes) {
            decompressor = new ZstdDictDecompress(bytes);
        }
        public void run() {
            compressors.values().forEach(ZstdDictCompress::close);
            decompressor.close();
        }
    }
}
