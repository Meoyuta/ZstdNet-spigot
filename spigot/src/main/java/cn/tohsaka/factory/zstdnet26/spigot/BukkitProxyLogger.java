package cn.tohsaka.factory.zstdnet26.spigot;

import cn.tohsaka.factory.zstdnet26.core.proxy.ProxyLogger;

import java.util.logging.Logger;

final class BukkitProxyLogger implements ProxyLogger {
    private final Logger logger;

    BukkitProxyLogger(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message) {
        logger.warning(message);
    }

    @Override
    public void error(String message) {
        logger.severe(message);
    }
}
