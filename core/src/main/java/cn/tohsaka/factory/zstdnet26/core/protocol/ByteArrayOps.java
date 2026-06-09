package cn.tohsaka.factory.zstdnet26.core.protocol;

import java.util.Arrays;

public final class ByteArrayOps {
    private ByteArrayOps() {
    }

    public static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] array : arrays) {
            total += array.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, out, offset, array.length);
            offset += array.length;
        }
        return out;
    }

    public static byte[] slice(byte[] data, int start, int end) {
        return Arrays.copyOfRange(data, start, end);
    }
}
