package cn.tohsaka.factory.zstdnet26.neoforge;

import cn.tohsaka.factory.zstdnet26.client.ZstdNetClient;
import cn.tohsaka.factory.zstdnet26.core.proxy.ProxyLogger;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("zstdnet")
public final class ZstdNetNeoForgeClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("ZstdNet");

    public ZstdNetNeoForgeClient() {
        ZstdNetClient.init(FMLPaths.CONFIGDIR.get(), new Slf4jProxyLogger(LOGGER));
    }

    private record Slf4jProxyLogger(Logger logger) implements ProxyLogger {
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
