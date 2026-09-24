package mys.zstdnet.reborn.neoforge.v26_1;

import mys.zstdnet.reborn.client.ZstdNetClient;
import mys.zstdnet.reborn.core.utils.ZstdNetLogger;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("zstdnet")
public final class ZstdNetNeoForgeClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("ZstdNet");

    public ZstdNetNeoForgeClient(IEventBus modEventBus) {
        ZstdNetClient.init(FMLPaths.CONFIGDIR.get(), new Slf4jLogger(LOGGER));
    }

    private record Slf4jLogger(Logger logger) implements ZstdNetLogger {
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
