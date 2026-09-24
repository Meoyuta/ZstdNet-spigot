package mys.zstdnet.reborn.spigot;

import mys.zstdnet.reborn.core.HostPort;
import mys.zstdnet.reborn.core.ZstdNetConfig;
import org.bukkit.configuration.file.FileConfiguration;


final class PluginConfig {
    private PluginConfig() {
    }

    static ZstdNetConfig loadSamePort(FileConfiguration config, int serverPort) {
        var port = serverPort > 0 ? serverPort : config.getInt("listen.port", 25565);
        var samePort = new HostPort(
            config.getString("listen.host", "0.0.0.0"),
            port
        );

        return new ZstdNetConfig(
            config.getBoolean("enabled", true),
            samePort,
            new HostPort("same-port", port),
            Math.clamp(config.getInt("compression-level", 9), 1, 22),
            config.getString("raw-login-message", "This server requires the ZstdNet client mod.")
        );
    }
}
