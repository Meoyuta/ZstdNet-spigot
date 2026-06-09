package cn.tohsaka.factory.zstdnet26.spigot;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

final class ServerPortSetup {
    private static final DateTimeFormatter BACKUP_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ZstdNetPlugin plugin;
    private final Path serverProperties;

    ServerPortSetup(ZstdNetPlugin plugin) {
        this.plugin = plugin;
        this.serverProperties = plugin.getServer().getWorldContainer().toPath().resolve("server.properties");
    }

    boolean ensureConfigured() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder: " + plugin.getDataFolder());
        }

        if (!plugin.getConfigFile().exists()) {
            try {
                createFirstRunConfig();
            } catch (IOException e) {
                plugin.getLogger().severe("Initial ZstdNet setup failed: " + e);
                return false;
            }
            plugin.reloadConfig();
            return false;
        }

        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("setup.pending-restart", false)) {
            return true;
        }

        int targetPort = config.getInt("target.port", -1);
        int currentServerPort = readServerPort(loadServerProperties(), -1);
        if (targetPort > 0 && currentServerPort == targetPort) {
            config.set("setup.pending-restart", false);
            plugin.saveConfig();
            plugin.getLogger().info("ZstdNet port migration is active. Public entry remains port " + config.getInt("listen.port") + ".");
            return true;
        }

        plugin.getLogger().warning("ZstdNet changed server.properties and needs a restart before the proxy can bind the public port.");
        return false;
    }

    boolean forceSetup() {
        return forceSetup(null);
    }

    boolean forceSetup(Integer publicPortOverride) {
        try {
            createFirstRunConfig(publicPortOverride);
            plugin.reloadConfig();
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("ZstdNet setup failed: " + e);
            return false;
        }
    }

    int currentServerPort() {
        return readServerPort(loadServerProperties(), -1);
    }

    Path serverPropertiesPath() {
        return serverProperties;
    }

    private void createFirstRunConfig() throws IOException {
        createFirstRunConfig(null);
    }

    private void createFirstRunConfig(Integer publicPortOverride) throws IOException {
        Properties props = loadServerProperties();
        FileConfiguration config = plugin.getConfig();
        int currentServerPort = readServerPort(props, 25565);
        int publicPort = resolvePublicPort(
            publicPortOverride,
            config.getInt("setup.original-server-port", -1),
            config.getInt("listen.port", -1),
            currentServerPort
        );
        int backendPort = preferredBackendPort(publicPort, currentServerPort, config.getInt("target.port", -1));
        if (backendPort <= 0) {
            backendPort = findBackendPort(publicPort);
        }

        backupServerProperties();
        props.setProperty("server-ip", "127.0.0.1");
        props.setProperty("server-port", Integer.toString(backendPort));
        try (OutputStream out = Files.newOutputStream(serverProperties)) {
            props.store(out, "Updated by ZstdNet. Backup was written before this change.");
        }

        config.set("enabled", true);
        config.set("listen.host", "0.0.0.0");
        config.set("listen.port", publicPort);
        config.set("target.host", "127.0.0.1");
        config.set("target.port", backendPort);
        config.set("compression-level", 9);
        config.set("flush-interval-ms", 2);
        config.set("stats-interval-seconds", 0);
        config.set("raw-login-message", "This server requires the ZstdNet client mod.");
        config.set("flood-guard.max-connections-per-ip", 9999);
        config.set("flood-guard.max-requests-per-window", 50);
        config.set("flood-guard.request-window-seconds", 10);
        config.set("flood-guard.ban-duration-seconds", 60);
        config.set("rate-limit.max-rate-per-connection-bps", 0);
        config.set("rate-limit.max-rate-global-bps", 0);
        config.set("rate-limit.burst-bytes", 262144);
        config.set("udp-routes.voice.enabled", false);
        config.set("udp-routes.voice.listen.host", "0.0.0.0");
        config.set("udp-routes.voice.listen.port", 24454);
        config.set("udp-routes.voice.target.host", "127.0.0.1");
        config.set("udp-routes.voice.target.port", 24454);
        config.set("setup.pending-restart", true);
        config.set("setup.original-server-port", publicPort);
        plugin.saveConfig();

        plugin.getLogger().warning("ZstdNet initial setup complete. Restart the server now.");
        plugin.getLogger().warning("Public ZSTD entry will use port " + publicPort + "; Spigot backend was moved to 127.0.0.1:" + backendPort + ".");
    }

    static int resolvePublicPort(Integer override, int originalServerPort, int listenPort, int currentServerPort) throws IOException {
        if (override != null) {
            return validatePort(override, "requested public port");
        }
        if (isValidPort(originalServerPort)) {
            return originalServerPort;
        }
        if (isValidPort(listenPort)) {
            return listenPort;
        }
        return validatePort(currentServerPort, "server.properties server-port");
    }

    static int preferredBackendPort(int publicPort, int currentServerPort, int targetPort) {
        if (isValidPort(targetPort) && targetPort != publicPort) {
            return targetPort;
        }
        if (isValidPort(currentServerPort) && currentServerPort != publicPort) {
            return currentServerPort;
        }
        return -1;
    }

    private static int validatePort(int port, String name) throws IOException {
        if (isValidPort(port)) {
            return port;
        }
        throw new IOException("invalid " + name + ": " + port);
    }

    private static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    private Properties loadServerProperties() {
        Properties props = new Properties();
        if (!Files.exists(serverProperties)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(serverProperties)) {
            props.load(in);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read server.properties: " + e);
        }
        return props;
    }

    private int readServerPort(Properties props, int fallback) {
        try {
            return Integer.parseInt(props.getProperty("server-port", Integer.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void backupServerProperties() throws IOException {
        if (!Files.exists(serverProperties)) {
            Files.createFile(serverProperties);
        }
        Path backup = serverProperties.resolveSibling("server.properties.zstdnet-" + BACKUP_SUFFIX.format(LocalDateTime.now()) + ".bak");
        Files.copy(serverProperties, backup);
        plugin.getLogger().info("Backed up server.properties to " + backup.getFileName());
    }

    private int findBackendPort(int publicPort) throws IOException {
        int start = publicPort < 65535 ? publicPort + 1 : 25566;
        for (int port = start; port <= 65535; port++) {
            if (port != publicPort && isAvailable(port)) {
                return port;
            }
        }
        for (int port = 1024; port < start; port++) {
            if (port != publicPort && isAvailable(port)) {
                return port;
            }
        }
        throw new IOException("no available backend port");
    }

    private boolean isAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}
