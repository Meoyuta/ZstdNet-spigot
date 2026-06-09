package cn.tohsaka.factory.zstdnet26.core.proxy;

import cn.tohsaka.factory.zstdnet26.core.HostPort;

public record UdpRoute(String name, HostPort listen, HostPort target) {
}
