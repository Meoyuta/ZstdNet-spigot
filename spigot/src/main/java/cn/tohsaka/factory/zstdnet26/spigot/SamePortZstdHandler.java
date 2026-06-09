package cn.tohsaka.factory.zstdnet26.spigot;

import cn.tohsaka.factory.zstdnet26.core.netty.ZstdFrameStats;
import cn.tohsaka.factory.zstdnet26.core.netty.MinecraftCompressionDisabler;
import cn.tohsaka.factory.zstdnet26.core.netty.ZstdNettyPipeline;
import cn.tohsaka.factory.zstdnet26.core.protocol.ByteArrayOps;
import cn.tohsaka.factory.zstdnet26.core.protocol.HandshakePacket;
import cn.tohsaka.factory.zstdnet26.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet26.core.protocol.ZstdFrameCodec;
import cn.tohsaka.factory.zstdnet26.core.proxy.ProxyLogger;
import cn.tohsaka.factory.zstdnet26.core.proxy.ZstdProxyConfig;
import cn.tohsaka.factory.zstdnet26.core.stats.TrafficStats;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class SamePortZstdHandler extends ByteToMessageDecoder {
    private enum Mode {
        UNDECIDED,
        RAW,
        ZSTD
    }

    private final ZstdProxyConfig config;
    private final TrafficStats stats;
    private final ProxyLogger logger;
    private Mode mode = Mode.UNDECIDED;

    SamePortZstdHandler(ZstdProxyConfig config, TrafficStats stats, ProxyLogger logger) {
        this.config = config;
        this.stats = stats;
        this.logger = logger;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (mode != Mode.UNDECIDED) {
            out.add(in.readRetainedSlice(in.readableBytes()));
            return;
        }

        if (in.readableBytes() < ZstdFrameCodec.MAGIC.length) {
            return;
        }
        if (startsWithMagic(in)) {
            in.skipBytes(ZstdFrameCodec.MAGIC.length);
            mode = Mode.ZSTD;
            countConnection(ctx);
            ZstdNettyPipeline.install(ctx.pipeline(), config.compressionLevel(), false, serverStats());
            MinecraftCompressionDisabler.install(ctx.pipeline());
            logger.info("accepted ZstdNet client connection from " + ctx.channel().remoteAddress());
            if (in.isReadable()) {
                out.add(in.readRetainedSlice(in.readableBytes()));
            }
            ctx.pipeline().remove(this);
            return;
        }

        Boolean rawLogin = isRawLogin(in);
        if (rawLogin == null) {
            return;
        }
        if (rawLogin) {
            logger.warn("rejected raw login from " + ctx.channel().remoteAddress());
            in.skipBytes(in.readableBytes());
            ctx.writeAndFlush(Unpooled.wrappedBuffer(loginDisconnectPacket(config.rawLoginMessage())))
                .addListener(ChannelFutureListener.CLOSE);
            return;
        }

        mode = Mode.RAW;
        if (in.isReadable()) {
            out.add(in.readRetainedSlice(in.readableBytes()));
        }
        ctx.pipeline().remove(this);
    }

    private void countConnection(ChannelHandlerContext ctx) {
        AtomicBoolean active = new AtomicBoolean(true);
        stats.addConnection(1);
        ctx.channel().closeFuture().addListener(future -> {
            if (active.compareAndSet(true, false)) {
                stats.addConnection(-1);
            }
        });
    }

    private ZstdFrameStats serverStats() {
        return new ZstdFrameStats() {
            @Override
            public void inbound(long rawBytes, long wireBytes) {
                stats.addRawUp(rawBytes);
                stats.addWireUp(wireBytes);
            }

            @Override
            public void outbound(long rawBytes, long wireBytes) {
                stats.addRawDown(rawBytes);
                stats.addWireDown(wireBytes);
            }
        };
    }

    private boolean startsWithMagic(ByteBuf in) {
        for (int i = 0; i < ZstdFrameCodec.MAGIC.length; i++) {
            if (in.getByte(in.readerIndex() + i) != ZstdFrameCodec.MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private Boolean isRawLogin(ByteBuf in) throws IOException {
        int start = in.readerIndex();
        Integer length = readVarInt(in);
        if (length == null) {
            in.readerIndex(start);
            return null;
        }
        if (length < 0 || length > 4096) {
            in.readerIndex(start);
            return false;
        }
        if (in.readableBytes() < length) {
            in.readerIndex(start);
            return null;
        }

        byte[] payload = new byte[length];
        in.readBytes(payload);
        in.readerIndex(start);
        HandshakePacket handshake = HandshakePacket.parse(payload);
        return handshake != null && handshake.nextState() == HandshakePacket.LOGIN;
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

    private static byte[] loginDisconnectPacket(String message) {
        String escaped = (message == null ? "ZstdNet required" : message)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
        byte[] json = ("{\"text\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8);
        byte[] payload = ByteArrayOps.concat(
            VarIntCodec.encode(0),
            VarIntCodec.encode(json.length),
            json
        );
        return ByteArrayOps.concat(VarIntCodec.encode(payload.length), payload);
    }
}
