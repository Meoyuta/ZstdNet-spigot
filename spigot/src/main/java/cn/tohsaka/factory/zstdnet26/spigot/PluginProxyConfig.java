package cn.tohsaka.factory.zstdnet26.spigot;

import cn.tohsaka.factory.zstdnet26.core.HostPort;
import cn.tohsaka.factory.zstdnet26.core.ZstdNetConfig;
import org.bukkit.configuration.file.FileConfiguration;


final class PluginProxyConfig {
    private PluginProxyConfig() {
    }

    static ZstdNetConfig loadSamePort(FileConfiguration config, int serverPort) {
        int port = serverPort > 0 ? serverPort : config.getInt("listen.port", 25565);
        HostPort samePort = new HostPort(
            config.getString("listen.host", "0.0.0.0"),
            port
        );

        return new ZstdNetConfig(
            config.getBoolean("enabled", true),
            samePort,
            new HostPort("same-port", port),
            clamp(config.getInt("compression-level", 9), 1, 22),
            config.getString("raw-login-message", "This server requires the ZstdNet client mod.")
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
