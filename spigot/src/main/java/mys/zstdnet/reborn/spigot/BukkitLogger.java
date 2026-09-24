package mys.zstdnet.reborn.spigot;

import mys.zstdnet.reborn.core.utils.ZstdNetLogger;

import java.util.logging.Logger;

final class BukkitLogger implements ZstdNetLogger {
    private final Logger logger;

    BukkitLogger(Logger logger) {
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
