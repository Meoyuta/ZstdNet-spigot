package cn.tohsaka.factory.zstdnet26.core.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public final class VarIntCodec {
    private static final int MAX_VARINT_BYTES = 5;

    private VarIntCodec() {
    }

    public static byte[] encode(int value) {
        byte[] out = new byte[MAX_VARINT_BYTES];
        int index = 0;
        int remaining = value;
        do {
            byte next = (byte) (remaining & 0x7F);
            remaining >>>= 7;
            if (remaining != 0) {
                next |= (byte) 0x80;
            }
            out[index++] = next;
        } while (remaining != 0);

        byte[] exact = new byte[index];
        System.arraycopy(out, 0, exact, 0, index);
        return exact;
    }

    public static int read(InputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        for (int i = 0; i < MAX_VARINT_BYTES; i++) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("unexpected eof while reading varint");
            }
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IOException("varint too large");
    }

    public static VarIntRead read(byte[] data, int offset) {
        return read(data, offset, data.length);
    }

    public static VarIntRead read(byte[] data, int offset, int limit) {
        int value = 0;
        int shift = 0;
        int max = Math.min(limit, offset + MAX_VARINT_BYTES);
        for (int i = offset; i < max; i++) {
            int b = data[i] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new VarIntRead(value, i + 1);
            }
            shift += 7;
        }
        return null;
    }
}
