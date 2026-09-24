package mys.zstdnet.reborn.fabric;

import mys.zstdnet.reborn.client.ZstdNetClient;
import mys.zstdnet.reborn.core.utils.ZstdNetLogger;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ZstdNetFabricClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ZstdNet");

    @Override
    public void onInitializeClient() {
        ZstdNetClient.init(FabricLoader.getInstance().getConfigDir(), new Slf4jProxyLogger(LOGGER));
    }

    private record Slf4jProxyLogger(Logger logger) implements ZstdNetLogger {
        @Override
        public void info(String message) {
            logger.info(message);
        }

        @Override
        public void warn(String message) {
            logger.warn(message);
        }

        @Override
        public void error(String message) {
            logger.error(message);
        }
    }
}
