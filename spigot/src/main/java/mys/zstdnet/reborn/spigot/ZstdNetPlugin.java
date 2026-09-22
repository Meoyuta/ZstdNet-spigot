package mys.zstdnet.reborn.spigot;

import mys.zstdnet.reborn.core.ZstdNetConfig;
import mys.zstdnet.reborn.core.stats.TrafficStats;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryStore;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryTrainer;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;

public final class ZstdNetPlugin extends JavaPlugin {
    private SamePortZstdInjector injector;
    private ZstdNetConfig activeConfig;
    private ServerPortSetup portSetup;
    private ZstdDictionaryStore dictionaryStore;
    private ZstdDictionaryTrainer dictionaryTrainer;

    @Override
    public void onEnable() {
        portSetup = new ServerPortSetup(this);
        dictionaryStore = new ZstdDictionaryStore(
            getDataFolder().toPath().resolve("dictionary.zdict"),
            new BukkitProxyLogger(getLogger())
        );
        dictionaryStore.load();
        dictionaryTrainer = new ZstdDictionaryTrainer(dictionaryStore, new BukkitProxyLogger(getLogger()));

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
        if (dictionaryTrainer != null) {
            dictionaryTrainer.close();
            dictionaryTrainer = null;
        }
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
        ZstdNetConfig config = PluginProxyConfig.loadSamePort(getConfig(), currentServerPort());
        try {
            SamePortZstdInjector next = new SamePortZstdInjector(
                config,
                new BukkitProxyLogger(getLogger()),
                dictionaryStore,
                dictionaryTrainer
            );
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

    ZstdNetConfig configuredConfig() {
        return PluginProxyConfig.loadSamePort(getConfig(), currentServerPort());
    }

    ZstdNetConfig activeConfig() {
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
        config.set("raw-login-message", config.getString("raw-login-message", "This server requires the ZstdNet client mod."));
        config.set("setup.pending-restart", false);
        config.set("setup.original-server-port", serverPort);
        saveConfig();
    }
}
