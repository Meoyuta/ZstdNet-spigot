package cn.tohsaka.factory.zstdnet26.core;

import java.net.InetSocketAddress;
import java.util.Objects;

public record HostPort(String host, int port) {
    public static final int DEFAULT_MINECRAFT_PORT = 25565;

    public HostPort {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host is blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    public static HostPort parse(String raw) {
        return parse(raw, DEFAULT_MINECRAFT_PORT);
    }

    public static HostPort parse(String raw, int defaultPort) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty address");
        }
        String value = raw.trim();
        if (value.startsWith("[") && value.contains("]")) {
            int end = value.indexOf(']');
            String host = normalizeHost(value.substring(1, end));
            int port = defaultPort;
            if (end + 1 < value.length() && value.charAt(end + 1) == ':') {
                port = Integer.parseInt(value.substring(end + 2).trim());
            }
            return new HostPort(host, port);
        }

        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            return new HostPort(
                normalizeHost(value.substring(0, lastColon)),
                Integer.parseInt(value.substring(lastColon + 1).trim())
            );
        }

        return new HostPort(normalizeHost(value), defaultPort);
    }

    public InetSocketAddress toAddress() {
        return new InetSocketAddress(host, port);
    }

    @Override
    public String toString() {
        return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
    }

    private static String normalizeHost(String host) {
        String normalized = host.trim();
        if (normalized.endsWith(".") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
