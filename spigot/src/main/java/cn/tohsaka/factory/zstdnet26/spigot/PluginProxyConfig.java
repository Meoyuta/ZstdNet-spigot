package cn.tohsaka.factory.zstdnet26.spigot;

import cn.tohsaka.factory.zstdnet26.core.HostPort;
import cn.tohsaka.factory.zstdnet26.core.proxy.UdpRoute;
import cn.tohsaka.factory.zstdnet26.core.proxy.ZstdProxyConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class PluginProxyConfig {
    private PluginProxyConfig() {
    }

    static ZstdProxyConfig load(FileConfiguration config) {
        HostPort listen = new HostPort(
            config.getString("listen.host", "0.0.0.0"),
            config.getInt("listen.port", 25565)
        );
        HostPort target = new HostPort(
            config.getString("target.host", "127.0.0.1"),
            config.getInt("target.port", 25566)
        );

        return new ZstdProxyConfig(
            config.getBoolean("enabled", true),
            listen,
            target,
            clamp(config.getInt("compression-level", 9), 1, 22),
            Duration.ofMillis(Math.max(0, config.getLong("flush-interval-ms", 2L))),
            Duration.ofSeconds(Math.max(0, config.getLong("stats-interval-seconds", 0L))),
            Math.max(0, config.getInt("flood-guard.max-connections-per-ip", 9999)),
            Math.max(0, config.getInt("flood-guard.max-requests-per-window", 50)),
            Duration.ofSeconds(Math.max(0, config.getLong("flood-guard.request-window-seconds", 10L))),
            Duration.ofSeconds(Math.max(0, config.getLong("flood-guard.ban-duration-seconds", 60L))),
            Math.max(0L, config.getLong("rate-limit.max-rate-per-connection-bps", 0L)),
            Math.max(0L, config.getLong("rate-limit.max-rate-global-bps", 0L)),
            Math.max(1024, config.getInt("rate-limit.burst-bytes", 262144)),
            loadUdpRoutes(config),
            config.getString("raw-login-message", "This server requires the ZstdNet client mod.")
        );
    }

    static ZstdProxyConfig loadSamePort(FileConfiguration config, int serverPort) {
        int port = serverPort > 0 ? serverPort : config.getInt("listen.port", 25565);
        HostPort samePort = new HostPort(
            config.getString("listen.host", "0.0.0.0"),
            port
        );

        return new ZstdProxyConfig(
            config.getBoolean("enabled", true),
            samePort,
            new HostPort("same-port", port),
            clamp(config.getInt("compression-level", 9), 1, 22),
            Duration.ofMillis(Math.max(0, config.getLong("flush-interval-ms", 2L))),
            Duration.ofSeconds(Math.max(0, config.getLong("stats-interval-seconds", 0L))),
            Math.max(0, config.getInt("flood-guard.max-connections-per-ip", 9999)),
            Math.max(0, config.getInt("flood-guard.max-requests-per-window", 50)),
            Duration.ofSeconds(Math.max(0, config.getLong("flood-guard.request-window-seconds", 10L))),
            Duration.ofSeconds(Math.max(0, config.getLong("flood-guard.ban-duration-seconds", 60L))),
            Math.max(0L, config.getLong("rate-limit.max-rate-per-connection-bps", 0L)),
            Math.max(0L, config.getLong("rate-limit.max-rate-global-bps", 0L)),
            Math.max(1024, config.getInt("rate-limit.burst-bytes", 262144)),
            loadUdpRoutes(config),
            config.getString("raw-login-message", "This server requires the ZstdNet client mod.")
        );
    }

    private static List<UdpRoute> loadUdpRoutes(FileConfiguration config) {
        List<UdpRoute> routes = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("udp-routes");
        if (section == null) {
            return routes;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection route = section.getConfigurationSection(key);
            if (route == null || !route.getBoolean("enabled", false)) {
                continue;
            }
            try {
                HostPort listen = new HostPort(
                    route.getString("listen.host", "0.0.0.0"),
                    route.getInt("listen.port")
                );
                HostPort target = new HostPort(
                    route.getString("target.host", "127.0.0.1"),
                    route.getInt("target.port")
                );
                routes.add(new UdpRoute(key, listen, target));
            } catch (RuntimeException ignored) {
            }
        }
        return routes;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
