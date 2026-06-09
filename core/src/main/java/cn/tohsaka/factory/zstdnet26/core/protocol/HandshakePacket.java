package cn.tohsaka.factory.zstdnet26.core.protocol;

import java.nio.charset.StandardCharsets;

public record HandshakePacket(int protocolVersion, String host, int port, int nextState) {
    public static final int STATUS = 1;
    public static final int LOGIN = 2;

    public static HandshakePacket parse(byte[] payload) {
        VarIntRead packetId = VarIntCodec.read(payload, 0);
        if (packetId == null || packetId.value() != 0) {
            return null;
        }

        VarIntRead protocol = VarIntCodec.read(payload, packetId.next());
        if (protocol == null) {
            return null;
        }

        VarIntRead hostLength = VarIntCodec.read(payload, protocol.next());
        if (hostLength == null || hostLength.value() < 0) {
            return null;
        }

        int hostStart = hostLength.next();
        int hostEnd = hostStart + hostLength.value();
        int portStart = hostEnd;
        int portEnd = portStart + 2;
        if (portEnd > payload.length) {
            return null;
        }

        VarIntRead nextState = VarIntCodec.read(payload, portEnd);
        if (nextState == null) {
            return null;
        }

        int port = ((payload[portStart] & 0xFF) << 8) | (payload[portStart + 1] & 0xFF);
        String host = new String(payload, hostStart, hostLength.value(), StandardCharsets.UTF_8);
        return new HandshakePacket(protocol.value(), host, port, nextState.value());
    }

    public static byte[] rewriteDestination(byte[] payload, String host, int port) {
        if (payload == null || payload.length == 0 || host == null || host.isBlank()) {
            return payload;
        }

        VarIntRead packetId = VarIntCodec.read(payload, 0);
        if (packetId == null || packetId.value() != 0) {
            return payload;
        }

        VarIntRead protocol = VarIntCodec.read(payload, packetId.next());
        if (protocol == null) {
            return payload;
        }

        VarIntRead hostLength = VarIntCodec.read(payload, protocol.next());
        if (hostLength == null || hostLength.value() < 0) {
            return payload;
        }

        int hostStart = hostLength.next();
        int hostEnd = hostStart + hostLength.value();
        int portEnd = hostEnd + 2;
        if (portEnd > payload.length) {
            return payload;
        }

        String originalHost = new String(payload, hostStart, hostLength.value(), StandardCharsets.UTF_8);
        byte[] hostBytes = (host + suffix(originalHost)).getBytes(StandardCharsets.UTF_8);
        return ByteArrayOps.concat(
            ByteArrayOps.slice(payload, 0, protocol.next()),
            VarIntCodec.encode(hostBytes.length),
            hostBytes,
            new byte[]{(byte) (port >>> 8), (byte) port},
            ByteArrayOps.slice(payload, portEnd, payload.length)
        );
    }

    private static String suffix(String host) {
        int marker = host == null ? -1 : host.indexOf('\0');
        return marker < 0 ? "" : host.substring(marker);
    }
}
