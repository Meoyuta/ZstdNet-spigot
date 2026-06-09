package cn.tohsaka.factory.zstdnet26.core.netty;

import cn.tohsaka.factory.zstdnet26.core.protocol.ZstdFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.io.IOException;
import java.util.List;

public final class ZstdNettyDecoder extends ByteToMessageDecoder {
    private final ZstdFrameStats stats;

    public ZstdNettyDecoder(ZstdFrameStats stats) {
        this.stats = stats == null ? ZstdFrameStats.NONE : stats;
    }

    ZstdNettyDecoder copyForMove() {
        return new ZstdNettyDecoder(stats);
    }

    @Override
    protected void decode(io.netty.channel.ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        while (in.isReadable()) {
            int frameStart = in.readerIndex();
            Integer rawLength = readVarInt(in);
            if (rawLength == null) {
                in.readerIndex(frameStart);
                return;
            }
            Integer storedLength = readVarInt(in);
            if (storedLength == null) {
                in.readerIndex(frameStart);
                return;
            }
            if (rawLength < 0 || rawLength > ZstdFrameCodec.MAX_FRAME_BYTES
                || storedLength < 0 || storedLength > ZstdFrameCodec.MAX_FRAME_BYTES) {
                throw new IOException("invalid zstd frame length");
            }
            int payloadLength = storedLength == 0 ? rawLength : storedLength;
            if (in.readableBytes() < payloadLength) {
                in.readerIndex(frameStart);
                return;
            }

            byte[] payload = new byte[payloadLength];
            in.readBytes(payload);
            byte[] raw = storedLength == 0 ? payload : ZstdFrameCodec.decompressFrame(payload, rawLength);
            stats.inbound(raw.length, in.readerIndex() - frameStart);
            out.add(Unpooled.wrappedBuffer(raw));
        }
    }

    private static Integer readVarInt(ByteBuf in) throws IOException {
        int value = 0;
        int shift = 0;
        for (int i = 0; i < 5; i++) {
            if (!in.isReadable()) {
                return null;
            }
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IOException("varint too large");
    }
}
