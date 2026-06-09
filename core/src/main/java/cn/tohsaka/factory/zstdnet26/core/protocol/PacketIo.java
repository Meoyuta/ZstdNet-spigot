package cn.tohsaka.factory.zstdnet26.core.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public final class PacketIo {
    private PacketIo() {
    }

    public static byte[] readPacket(InputStream in) throws IOException {
        int length = VarIntCodec.read(in);
        if (length <= 0) {
            return new byte[0];
        }
        return readFully(in, length);
    }

    public static byte[] readPacketWire(InputStream in, int maxPayloadBytes) throws IOException {
        byte[] prefix = new byte[5];
        int prefixLength = 0;
        VarIntRead lengthRead = null;

        while (prefixLength < prefix.length && lengthRead == null) {
            int next = in.read();
            if (next < 0) {
                throw new EOFException("unexpected eof while reading packet length");
            }
            prefix[prefixLength++] = (byte) next;
            lengthRead = VarIntCodec.read(prefix, 0, prefixLength);
        }

        if (lengthRead == null) {
            throw new IOException("packet length varint too large");
        }
        if (lengthRead.value() < 0 || lengthRead.value() > maxPayloadBytes) {
            throw new IOException("packet too large: " + lengthRead.value());
        }

        byte[] payload = readFully(in, lengthRead.value());
        return ByteArrayOps.concat(Arrays.copyOf(prefix, prefixLength), payload);
    }

    public static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) {
                throw new EOFException("unexpected eof");
            }
            offset += read;
        }
        return data;
    }

    public static void writePacket(OutputStream out, byte[] payload) throws IOException {
        out.write(VarIntCodec.encode(payload.length));
        if (payload.length > 0) {
            out.write(payload);
        }
    }

    public static byte[] extractPacketPayload(byte[] packetWire) throws IOException {
        VarIntRead length = VarIntCodec.read(packetWire, 0, packetWire.length);
        if (length == null || length.value() < 0 || length.next() + length.value() > packetWire.length) {
            throw new IOException("invalid packet");
        }
        return Arrays.copyOfRange(packetWire, length.next(), length.next() + length.value());
    }
}
