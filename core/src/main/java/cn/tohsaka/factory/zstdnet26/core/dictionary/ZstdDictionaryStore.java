package cn.tohsaka.factory.zstdnet26.core.dictionary;

import cn.tohsaka.factory.zstdnet26.core.proxy.ProxyLogger;

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
            dictionary = ZstdDictionary.fromBytes(Files.readAllBytes(dictionaryPath));
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
        return save(Files.readAllBytes(normalized));
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
