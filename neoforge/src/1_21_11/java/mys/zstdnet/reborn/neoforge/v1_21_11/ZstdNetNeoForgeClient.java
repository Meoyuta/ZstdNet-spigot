package mys.zstdnet.reborn.neoforge.v1_21_11;

import mys.zstdnet.reborn.client.ZstdNetClient;
import mys.zstdnet.reborn.core.utils.ZstdNetLogger;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("zstdnet")
public final class ZstdNetNeoForgeClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("ZstdNet");

    public ZstdNetNeoForgeClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(this::registerClientPayloadHandlers);
        ZstdNetClient.init(FMLPaths.CONFIGDIR.get(), new Slf4jProxyLogger(LOGGER));
    }

    private void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(BenchmarkInfoPayload.TYPE, BenchmarkInfoPayload.STREAM_CODEC);
    }

    private void registerClientPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(BenchmarkInfoPayload.TYPE, (payload, context) -> BenchmarkInfoClientState.receive(payload));
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
