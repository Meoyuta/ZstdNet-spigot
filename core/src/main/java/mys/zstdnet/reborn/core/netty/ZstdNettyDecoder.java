package mys.zstdnet.reborn.core.netty;

import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.io.IOException;
import java.util.List;

public final class ZstdNettyDecoder extends ByteToMessageDecoder {
    private final ZstdFrameStats stats;
    private final ZstdDictionarySession dictionarySession;
    private boolean streamHeaderRead;

    public ZstdNettyDecoder(ZstdFrameStats stats) {
        this(stats, null);
    }

    public ZstdNettyDecoder(ZstdFrameStats stats, ZstdDictionarySession dictionarySession) {
        this.stats = stats == null ? ZstdFrameStats.NONE : stats;
        this.dictionarySession = dictionarySession;
    }

    ZstdNettyDecoder copyForMove() {
        var copy = new ZstdNettyDecoder(stats, dictionarySession);
        copy.streamHeaderRead = streamHeaderRead;
        return copy;
    }

    @Override
    protected void decode(io.netty.channel.ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (dictionarySession != null && dictionarySession.expectsHeader() && !streamHeaderRead) {
            if (!ZstdStreamHeader.read(in)) {
                return;
            }
            streamHeaderRead = true;
        }
        while (in.isReadable()) {
            var frameStart = in.readerIndex();
            var rawLength = readVarInt(in);
            if (rawLength == null) {
                in.readerIndex(frameStart);
                return;
            }
            var storedTag = readVarInt(in);
            if (storedTag == null) {
                in.readerIndex(frameStart);
                return;
            }
            if (rawLength < 0 || rawLength > ZstdFrameCodec.MAX_FRAME_BYTES
                || storedTag < 0 || storedTag > (ZstdFrameCodec.MAX_FRAME_BYTES << 1) + 1) {
                throw new IOException("invalid zstd frame length");
            }
            var payloadLength = storedTag == 0 ? rawLength : storedTag >>> 1;
            if (rawLength == 0) {
                if (storedTag == 0 || (storedTag & 1) != 0 || payloadLength == 0
                    || payloadLength > mys.zstdnet.reborn.core.dictionary.ZstdDictionary.MAX_BYTES + 13) {
                    throw new IOException("invalid ZstdNet control frame");
                }
                var payloadStart = in.readerIndex();
                if (in.readableBytes() < payloadLength) {
                    if (dictionarySession != null) {
                        dictionarySession.observeControlProgress(in, payloadStart, payloadLength);
                    }
                    in.readerIndex(frameStart);
                    return;
                }
                var control = new byte[payloadLength];
                in.readBytes(control);
                if (dictionarySession == null) {
                    throw new IOException("received ZstdNet control frame without a session");
                }
                dictionarySession.receiveControl(control);
                if (dictionarySession.hasPendingControl()) {
                    // Start at the encoder context: bypass Minecraft's packet encoder and length prepender.
                    var encoder = ctx.pipeline().context(ZstdNettyPipeline.OUTBOUND_HANDLER);
                    if (encoder == null) throw new IOException("Missing ZstdNet encoder for dictionary acknowledgement");
                    ((ZstdNettyEncoder) encoder.handler()).write(encoder, Unpooled.buffer(0), ctx.newPromise());
                    encoder.flush();
                }
                continue;
            }
            if (payloadLength == 0) {
                throw new IOException("invalid zstd frame payload length");
            }
            if (in.readableBytes() < payloadLength) {
                in.readerIndex(frameStart);
                return;
            }

            var payload = new byte[payloadLength];
            in.readBytes(payload);
            var usesDictionary = storedTag != 0 && (storedTag & 1) == 1;
            mys.zstdnet.reborn.core.dictionary.ZstdDictionary dictionary = dictionarySession == null
                ? null
                : dictionarySession.activeDictionary();
            if (usesDictionary && dictionary == null) {
                throw new IOException("received dictionary-compressed ZstdNet frame before dictionary activation");
            }
            var raw = storedTag == 0 ? payload : ZstdFrameCodec.decompressFrame(payload, rawLength, usesDictionary ? dictionary : null);
            stats.inbound(raw.length, in.readerIndex() - frameStart);
            stats.inboundSample(raw);
            out.add(Unpooled.wrappedBuffer(raw));
        }
    }

    @Override
    public void channelInactive(io.netty.channel.ChannelHandlerContext ctx) throws Exception {
        try {
            super.channelInactive(ctx);
        } finally {
            if (dictionarySession != null) dictionarySession.disconnected();
        }
    }

    @Override
    public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
        if (dictionarySession != null) dictionarySession.disconnected();
        ctx.fireExceptionCaught(cause);
        ctx.close();
    }

    private static Integer readVarInt(ByteBuf in) throws IOException {
        var value = 0;
        var shift = 0;
        for (var i = 0; i < 5; i++) {
            if (!in.isReadable()) {
                return null;
            }
            var b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IOException("varint too large");
    }
}
