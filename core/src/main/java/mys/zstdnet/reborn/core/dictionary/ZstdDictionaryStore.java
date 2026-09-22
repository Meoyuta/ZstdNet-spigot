package mys.zstdnet.reborn.core.dictionary;

import mys.zstdnet.reborn.core.proxy.ProxyLogger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public final class ZstdDictionaryStore {
    private final Path dictionaryPath;
    private final ProxyLogger logger;
    private volatile ZstdDictionary dictionary;

    public ZstdDictionaryStore(Path dictionaryPath, ProxyLogger logger) {
        this.dictionaryPath = Objects.requireNonNull(dictionaryPath, "dictionaryPath").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Path dictionaryPath() {
        return dictionaryPath;
    }

    public ZstdDictionary dictionary() {
        return dictionary;
    }

    public synchronized boolean load() {
        if (!Files.isRegularFile(dictionaryPath)) {
            dictionary = null;
            return false;
        }
        try {
            dictionary = ZstdDictionary.fromBytes(readBounded(dictionaryPath));
            logger.info("loaded ZstdNet dictionary id=" + Long.toUnsignedString(dictionary.id()) + " size=" + dictionary.size());
            return true;
        } catch (IOException | RuntimeException e) {
            dictionary = null;
            logger.warn("ignored invalid ZstdNet dictionary at " + dictionaryPath + ": " + e.getMessage());
            return false;
        }
    }

    public synchronized ZstdDictionary save(byte[] bytes) throws IOException {
        ZstdDictionary next = ZstdDictionary.fromBytes(bytes);
        write(next.bytes());
        dictionary = next;
        logger.info("saved ZstdNet dictionary id=" + Long.toUnsignedString(next.id()) + " size=" + next.size());
        return next;
    }

    public synchronized ZstdDictionary importFrom(Path source) throws IOException {
        Path normalized = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("dictionary file does not exist: " + normalized);
        }
        return save(readBounded(normalized));
    }

    public synchronized Path export() throws IOException {
        if (dictionary == null) throw new IOException("No dictionary is loaded; train or import one first");
        Path directory = dictionaryPath.getParent().resolve("exports");
        Files.createDirectories(directory);
        Path exported = Files.createTempFile(directory, "dictionary-" + Long.toUnsignedString(dictionary.id()) + "-", ".zdict");
        Files.write(exported, dictionary.bytes());
        return exported.toAbsolutePath().normalize();
    }

    private static byte[] readBounded(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(ZstdDictionary.MAX_BYTES + 1);
            if (bytes.length > ZstdDictionary.MAX_BYTES) throw new IOException("Dictionary is too large");
            return bytes;
        }
    }

    private void write(byte[] bytes) throws IOException {
        Path parent = dictionaryPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = dictionaryPath.resolveSibling(dictionaryPath.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, dictionaryPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, dictionaryPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
