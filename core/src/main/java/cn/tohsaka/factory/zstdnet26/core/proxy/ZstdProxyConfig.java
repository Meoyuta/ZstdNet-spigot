package cn.tohsaka.factory.zstdnet26.core.proxy;

import cn.tohsaka.factory.zstdnet26.core.HostPort;

import java.time.Duration;
import java.util.List;

public record ZstdProxyConfig(
    boolean enabled,
    HostPort listen,
    HostPort target,
    int compressionLevel,
    Duration flushInterval,
    Duration statsInterval,
    int maxConnectionsPerIp,
    int maxRequestsPerWindow,
    Duration requestWindow,
    Duration banDuration,
    long maxRatePerConnectionBps,
    long maxRateGlobalBps,
    int burstBytes,
    List<UdpRoute> udpRoutes,
    String rawLoginMessage
) {
    public static ZstdProxyConfig defaults(HostPort listen, HostPort target) {
        return new ZstdProxyConfig(
            true,
            listen,
            target,
            9,
            Duration.ofMillis(2),
            Duration.ZERO,
            9999,
            50,
            Duration.ofSeconds(10),
            Duration.ofMinutes(1),
            0L,
            0L,
            256 * 1024,
            List.of(),
            "This server requires the ZstdNet client mod."
        );
    }
}
