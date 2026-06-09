package cn.tohsaka.factory.zstdnet26.core.protocol;

import com.github.luben.zstd.Zstd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class ZstdFrameCodec {
    public static final byte[] MAGIC = new byte[]{0x28, (byte) 0xB5, 0x2F, (byte) 0xFD};
    public static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    private ZstdFrameCodec() {
    }

    public static byte[] compressFrame(byte[] raw, int level) throws IOException {
        byte[] compressed = Zstd.compress(raw, level);
        boolean storeRaw = compressed.length >= raw.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(raw.length, compressed.length) + 10);
        out.write(VarIntCodec.encode(raw.length));
        if (storeRaw) {
            out.write(VarIntCodec.encode(0));
            out.write(raw);
        } else {
            out.write(VarIntCodec.encode(compressed.length));
            out.write(compressed);
        }
        return out.toByteArray();
    }

    public static byte[] readFrame(InputStream in) throws IOException {
        int rawLength = VarIntCodec.read(in);
        int storedLength = VarIntCodec.read(in);
        if (rawLength < 0 || rawLength > MAX_FRAME_BYTES || storedLength < 0 || storedLength > MAX_FRAME_BYTES) {
            throw new IOException("invalid zstd frame length");
        }
        if (storedLength == 0) {
            return PacketIo.readFully(in, rawLength);
        }
        byte[] compressed = PacketIo.readFully(in, storedLength);
        return decompressFrame(compressed, rawLength);
    }

    public static byte[] decompressFrame(byte[] compressed, int rawLength) throws IOException {
        byte[] raw = Zstd.decompress(compressed, rawLength);
        if (raw.length != rawLength) {
            throw new IOException("zstd frame length mismatch");
        }
        return raw;
    }
}
