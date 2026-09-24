package mys.zstdnet.reborn.client;

import mys.zstdnet.reborn.core.utils.ZstdNetLogger;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionary;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryStore;
import mys.zstdnet.reborn.core.netty.ZstdDictionaryDownloadListener;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class ZstdNetClient {
    private static volatile ClientConfig config;
    private static volatile ZstdNetLogger logger;
    private static volatile ZstdDictionaryStore dictionaryStore;
    private static volatile java.util.function.Supplier<ZstdDictionaryDownloadListener> dictionaryDownloadListeners =
        () -> ZstdDictionaryDownloadListener.NONE;

    private ZstdNetClient() {
    }

    public static void init(Path configDir, ZstdNetLogger proxyLogger) {
        logger = Objects.requireNonNull(proxyLogger, "proxyLogger");
        config = ClientConfig.load(configDir);
        dictionaryStore = new ZstdDictionaryStore(configDir.resolve("zstdnet").resolve("dictionary.zdict"), logger);
        logger.info("ZstdNet client initialized");
    }

    public static ClientConfig config() {
        ClientConfig current = config;
        if (current == null) {
            return ClientConfig.load(Path.of("config"));
        }
        return current;
    }

    public static ZstdNetLogger logger() {
        ZstdNetLogger current = logger;
        if (current != null) {
            return current;
        }
        return new ZstdNetLogger() {
            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message) {
            }

            @Override
            public void error(String message) {
            }
        };
    }

    public static void setDictionaryDownloadListener(ZstdDictionaryDownloadListener listener) {
        dictionaryDownloadListeners = () -> listener == null ? ZstdDictionaryDownloadListener.NONE : listener;
    }

    public static void setDictionaryDownloadListenerFactory(java.util.function.Supplier<ZstdDictionaryDownloadListener> factory) {
        dictionaryDownloadListeners = Objects.requireNonNull(factory);
    }

    public static ZstdDictionaryDownloadListener dictionaryDownloadListener() {
        return dictionaryDownloadListeners.get();
    }

    public static ZstdDictionary receiveServerDictionary(long expectedId, byte[] bytes) throws IOException {
        ZstdDictionaryStore store = dictionaryStore;
        if (store == null) {
            store = new ZstdDictionaryStore(Path.of("config", "zstdnet", "dictionary.zdict"), logger());
            dictionaryStore = store;
        }
        ZstdDictionary dictionary = ZstdDictionary.fromBytes(bytes);
        if (dictionary.id() != expectedId) {
            throw new IOException("server dictionary id does not match its payload");
        }
        try {
            store.save(bytes);
        } catch (IOException e) {
            logger().warn("Could not cache server dictionary; using it in memory: " + e.getMessage());
        }
        return dictionary;
    }

    public static Path dictionaryPath() {
        ZstdDictionaryStore store = dictionaryStore;
        if (store == null) {
            return Path.of("config", "zstdnet", "dictionary.zdict").toAbsolutePath().normalize();
        }
        return store.dictionaryPath();
    }
}
