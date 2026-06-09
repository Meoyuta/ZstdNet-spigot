package cn.tohsaka.factory.zstdnet26.client;

import cn.tohsaka.factory.zstdnet26.core.proxy.ProxyLogger;

import java.nio.file.Path;
import java.util.Objects;

public final class ZstdNetClient {
    private static volatile ClientConfig config;
    private static volatile ProxyLogger logger;

    private ZstdNetClient() {
    }

    public static void init(Path configDir, ProxyLogger proxyLogger) {
        logger = Objects.requireNonNull(proxyLogger, "proxyLogger");
        config = ClientConfig.load(configDir);
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
}
