package cn.tohsaka.factory.zstdnet26.client;

import cn.tohsaka.factory.zstdnet26.core.proxy.ProxyLogger;
import cn.tohsaka.factory.zstdnet26.core.dictionary.ZstdDictionary;
import cn.tohsaka.factory.zstdnet26.core.dictionary.ZstdDictionaryStore;
import cn.tohsaka.factory.zstdnet26.core.netty.ZstdDictionaryDownloadListener;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class ZstdNetClient {
    private static volatile ClientConfig config;
    private static volatile ProxyLogger logger;
    private static volatile ZstdDictionaryStore dictionaryStore;
    private static volatile ZstdDictionaryDownloadListener dictionaryDownloadListener = ZstdDictionaryDownloadListener.NONE;

    private ZstdNetClient() {
    }

    public static void init(Path configDir, ProxyLogger proxyLogger) {
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

    public static ProxyLogger logger() {
        ProxyLogger current = logger;
        if (current != null) {
            return current;
        }
        return new ProxyLogger() {
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
        dictionaryDownloadListener = listener == null ? ZstdDictionaryDownloadListener.NONE : listener;
    }

    public static ZstdDictionaryDownloadListener dictionaryDownloadListener() {
        return dictionaryDownloadListener;
    }

    public static ZstdDictionary receiveServerDictionary(long expectedId, byte[] bytes) throws IOException {
        ZstdDictionaryStore store = dictionaryStore;
        if (store == null) {
            store = new ZstdDictionaryStore(Path.of("config", "zstdnet", "dictionary.zdict"), logger());
            dictionaryStore = store;
        }
        ZstdDictionary dictionary = store.save(bytes);
        if (dictionary.id() != expectedId) {
            throw new IOException("server dictionary id does not match its payload");
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
