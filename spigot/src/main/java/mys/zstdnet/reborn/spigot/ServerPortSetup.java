package mys.zstdnet.reborn.spigot;

import java.nio.file.Path;

/** Reports the actual listener port; same-port injection never migrates server.properties. */
final class ServerPortSetup {
    private final ZstdNetPlugin plugin;

    ServerPortSetup(ZstdNetPlugin plugin) {
        this.plugin = plugin;
    }

    int currentServerPort() {
        return plugin.getServer().getPort();
    }

    Path serverPropertiesPath() {
        return plugin.getServer().getWorldContainer().toPath().resolve("server.properties");
    }
}
