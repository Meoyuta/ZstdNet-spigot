package cn.tohsaka.factory.zstdnet26.core.netty;

import cn.tohsaka.factory.zstdnet26.core.protocol.ZstdFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.MessageToByteEncoder;

public final class ZstdNettyEncoder extends MessageToByteEncoder<ByteBuf> {
    private final int level;
    private final boolean sendMagic;
    private final ZstdFrameStats stats;
    private boolean magicSent;

    public ZstdNettyEncoder(int level, boolean sendMagic, ZstdFrameStats stats) {
        this.level = level;
        this.sendMagic = sendMagic;
        this.stats = stats == null ? ZstdFrameStats.NONE : stats;
    }

    ZstdNettyEncoder copyForMove() {
        ZstdNettyEncoder copy = new ZstdNettyEncoder(level, sendMagic, stats);
        copy.magicSent = magicSent;
        return copy;
    }

    @Override
    protected void encode(io.netty.channel.ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        int readable = msg.readableBytes();
        if (readable <= 0) {
            return;
        }

        byte[] raw = ByteBufUtil.getBytes(msg, msg.readerIndex(), readable, false);
        byte[] frame = ZstdFrameCodec.compressFrame(raw, level);
        int wireBytes = frame.length;
        if (sendMagic && !magicSent) {
            out.writeBytes(ZstdFrameCodec.MAGIC);
            wireBytes += ZstdFrameCodec.MAGIC.length;
            magicSent = true;
        }
        out.writeBytes(frame);
        stats.outbound(raw.length, wireBytes);
    }
}
