package cn.tohsaka.factory.zstdnet26.fabric;

import cn.tohsaka.factory.zstdnet26.client.ZstdNetConnectionHooks;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

public final class ZstdNetConnectHooks {
    private ZstdNetConnectHooks() {
    }

    public static ServerAddress intercept(ServerAddress original) {
        if (original == null) {
            return null;
        }

        ZstdNetConnectionHooks.prepare(original.getHost(), original.getPort());
        return original;
    }
}
