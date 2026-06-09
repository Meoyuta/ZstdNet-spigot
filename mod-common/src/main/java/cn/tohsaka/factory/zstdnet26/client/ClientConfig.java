package cn.tohsaka.factory.zstdnet26.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public final class ClientConfig {
    private final boolean enabled;
    private final int compressionLevel;
    private final Set<String> servers;

    private ClientConfig(boolean enabled, int compressionLevel, Set<String> servers) {
        this.enabled = enabled;
        this.compressionLevel = Math.max(1, Math.min(22, compressionLevel));
        this.servers = servers;
    }

    public static ClientConfig load(Path configDir) {
        Path path = configDir.resolve("zstdnet-client.properties");
        Properties props = new Properties();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException ignored) {
            }
        } else {
            props.setProperty("enabled", "true");
            props.setProperty("compression-level", "9");
            props.setProperty("servers", "*");
            try {
                Files.createDirectories(configDir);
                try (OutputStream out = Files.newOutputStream(path)) {
                    props.store(out, "ZstdNet client configuration");
                }
            } catch (IOException ignored) {
            }
        }

        Set<String> servers = Arrays.stream(props.getProperty("servers", "*").split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

        int level = parseInt(props.getProperty("compression-level"), 9);
        return new ClientConfig(Boolean.parseBoolean(props.getProperty("enabled", "true")), level, servers);
    }

    public boolean enabledFor(String host, int port) {
        if (!enabled || host == null || host.isBlank()) {
            return false;
        }
        if (servers.contains("*")) {
            return true;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return servers.contains(normalizedHost) || servers.contains(normalizedHost + ":" + port);
    }

    public int compressionLevel() {
        return compressionLevel;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
