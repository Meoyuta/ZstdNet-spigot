package cn.tohsaka.factory.zstdnet26.spigot;

import cn.tohsaka.factory.zstdnet26.core.proxy.ZstdProxyConfig;
import cn.tohsaka.factory.zstdnet26.core.stats.TrafficStats;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;

public final class ZstdNetPlugin extends JavaPlugin {
    private SamePortZstdInjector injector;
    private ZstdProxyConfig activeConfig;
    private ServerPortSetup portSetup;

    @Override
    public void onEnable() {
        portSetup = new ServerPortSetup(this);

        PluginCommand command = getCommand("zstdnet");
        if (command != null) {
            ZstdNetCommand executor = new ZstdNetCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        ensureSamePortConfig();
        startProxy();
    }

    @Override
    public void onDisable() {
        stopProxy();
    }

    File getConfigFile() {
        return new File(getDataFolder(), "config.yml");
    }

    boolean runSetup() {
        return runSetup(null);
    }

    boolean runSetup(Integer publicPortOverride) {
        stopProxy();
        ensureSamePortConfig(publicPortOverride);
        reloadConfig();
        return true;
    }

    boolean startProxy() {
        if (injector != null) {
            return true;
        }
        reloadConfig();
        ZstdProxyConfig config = PluginProxyConfig.loadSamePort(getConfig(), currentServerPort());
        try {
            SamePortZstdInjector next = new SamePortZstdInjector(config, new BukkitProxyLogger(getLogger()));
            next.inject();
            injector = next;
            activeConfig = config;
            return true;
        } catch (Exception e) {
            getLogger().severe("Could not inject ZstdNet into the Minecraft Netty listener: " + e);
            return false;
        }
    }

    void stopProxy() {
        if (injector != null) {
            injector.close();
            injector = null;
            activeConfig = null;
        }
    }

    boolean reloadProxy() {
        stopProxy();
        return startProxy();
    }

    boolean isProxyRunning() {
        return injector != null;
    }

    boolean isSetupPendingRestart() {
        return false;
    }

    int currentServerPort() {
        return portSetup == null ? -1 : portSetup.currentServerPort();
    }

    Path serverPropertiesPath() {
        return portSetup == null ? null : portSetup.serverPropertiesPath();
    }

    ZstdProxyConfig configuredConfig() {
        return PluginProxyConfig.loadSamePort(getConfig(), currentServerPort());
    }

    ZstdProxyConfig activeConfig() {
        return activeConfig;
    }

    TrafficStats.Snapshot proxyStats() {
        return injector == null ? null : injector.snapshot();
    }

    private void ensureSamePortConfig() {
        ensureSamePortConfig(null);
    }

    private void ensureSamePortConfig(Integer publicPortOverride) {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder: " + getDataFolder());
        }
        FileConfiguration config = getConfig();
        int serverPort = publicPortOverride == null ? currentServerPort() : publicPortOverride;
        if (serverPort <= 0) {
            serverPort = config.getInt("listen.port", 25565);
        }

        config.set("mode", "same-port-injection");
        config.set("enabled", config.getBoolean("enabled", true));
        config.set("listen.host", "0.0.0.0");
        config.set("listen.port", serverPort);
        config.set("target.host", "same-port");
        config.set("target.port", serverPort);
        config.set("compression-level", config.getInt("compression-level", 9));
        config.set("flush-interval-ms", config.getLong("flush-interval-ms", 2L));
        config.set("stats-interval-seconds", config.getLong("stats-interval-seconds", 0L));
        config.set("raw-login-message", config.getString("raw-login-message", "This server requires the ZstdNet client mod."));
        config.set("flood-guard.max-connections-per-ip", config.getInt("flood-guard.max-connections-per-ip", 9999));
        config.set("flood-guard.max-requests-per-window", config.getInt("flood-guard.max-requests-per-window", 50));
        config.set("flood-guard.request-window-seconds", config.getInt("flood-guard.request-window-seconds", 10));
        config.set("flood-guard.ban-duration-seconds", config.getInt("flood-guard.ban-duration-seconds", 60));
        config.set("rate-limit.max-rate-per-connection-bps", config.getLong("rate-limit.max-rate-per-connection-bps", 0L));
        config.set("rate-limit.max-rate-global-bps", config.getLong("rate-limit.max-rate-global-bps", 0L));
        config.set("rate-limit.burst-bytes", config.getInt("rate-limit.burst-bytes", 262144));
        config.set("setup.pending-restart", false);
        config.set("setup.original-server-port", serverPort);
        saveConfig();
    }
}
