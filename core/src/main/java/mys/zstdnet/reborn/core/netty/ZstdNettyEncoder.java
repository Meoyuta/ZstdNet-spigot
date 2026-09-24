package mys.zstdnet.reborn.core.netty;

import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;
import mys.zstdnet.reborn.core.protocol.VarIntCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.MessageToByteEncoder;

public final class ZstdNettyEncoder extends MessageToByteEncoder<ByteBuf> {
    private final int level;
    private final boolean sendMagic;
    private final ZstdFrameStats stats;
    private final ZstdDictionarySession dictionarySession;
    private boolean magicSent;
    private boolean streamHeaderSent;

    public ZstdNettyEncoder(int level, boolean sendMagic, ZstdFrameStats stats) {
        this(level, sendMagic, stats, null);
    }

    public ZstdNettyEncoder(int level, boolean sendMagic, ZstdFrameStats stats, ZstdDictionarySession dictionarySession) {
        this.level = level;
        this.sendMagic = sendMagic;
        this.stats = stats == null ? ZstdFrameStats.NONE : stats;
        this.dictionarySession = dictionarySession;
    }

    ZstdNettyEncoder copyForMove() {
        var copy = new ZstdNettyEncoder(level, sendMagic, stats, dictionarySession);
        copy.magicSent = magicSent;
        copy.streamHeaderSent = streamHeaderSent;
        return copy;
    }

    @Override
    protected void encode(io.netty.channel.ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        var readable = msg.readableBytes();
        if (readable <= 0 && (dictionarySession == null || !dictionarySession.hasPendingControl())) {
            return;
        }

        var wireBytes = 0;
        if (sendMagic && !magicSent) {
            out.writeBytes(ZstdFrameCodec.MAGIC);
            wireBytes += ZstdFrameCodec.MAGIC.length;
            magicSent = true;
        }
        if (dictionarySession != null && !streamHeaderSent) {
            ZstdStreamHeader.write(out);
            wireBytes += ZstdStreamHeader.BYTES;
            streamHeaderSent = true;
        }
        if (dictionarySession != null) {
            byte[] control;
            while ((control = dictionarySession.pollOutboundControl()) != null) {
                var record = controlRecord(control);
                out.writeBytes(record);
                wireBytes += record.length;
            }
        }
        if (readable == 0) {
            stats.outbound(0, wireBytes);
            return;
        }
        var raw = ByteBufUtil.getBytes(msg, msg.readerIndex(), readable, false);
        mys.zstdnet.reborn.core.dictionary.ZstdDictionary dictionary = dictionarySession == null
            ? null
            : dictionarySession.activeDictionary();
        var frame = ZstdFrameCodec.compressFrame(raw, level, dictionary);
        wireBytes += frame.length;
        out.writeBytes(frame);
        stats.outbound(raw.length, wireBytes);
        stats.outboundSample(raw);
    }

    private static byte[] controlRecord(byte[] control) throws java.io.IOException {
        var out = new java.io.ByteArrayOutputStream(control.length + 10);
        out.write(VarIntCodec.encode(0));
        out.write(VarIntCodec.encode(control.length << 1));
        out.write(control);
        return out.toByteArray();
    }
}
