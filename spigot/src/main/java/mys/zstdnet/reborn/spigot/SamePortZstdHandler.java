package mys.zstdnet.reborn.spigot;

import mys.zstdnet.reborn.core.netty.ZstdFrameStats;
import mys.zstdnet.reborn.core.netty.MinecraftCompressionDisabler;
import mys.zstdnet.reborn.core.netty.ZstdDictionarySession;
import mys.zstdnet.reborn.core.netty.ZstdNettyPipeline;
import mys.zstdnet.reborn.core.netty.ZstdStreamHeader;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryStore;
import mys.zstdnet.reborn.core.dictionary.ZstdDictionaryTrainer;
import mys.zstdnet.reborn.core.protocol.ByteArrayOps;
import mys.zstdnet.reborn.core.protocol.HandshakePacket;
import mys.zstdnet.reborn.core.protocol.VarIntCodec;
import mys.zstdnet.reborn.core.protocol.ZstdFrameCodec;
import mys.zstdnet.reborn.core.utils.ZstdNetLogger;
import mys.zstdnet.reborn.core.ZstdNetConfig;
import mys.zstdnet.reborn.core.stats.TrafficStats;
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

    private final ZstdNetConfig config;
    private final TrafficStats stats;
    private final ZstdNetLogger logger;
    private final ZstdDictionaryStore dictionaryStore;
    private final ZstdDictionaryTrainer dictionaryTrainer;
    private Mode mode = Mode.UNDECIDED;
    private boolean streamHeaderRead;

    SamePortZstdHandler(
        ZstdNetConfig config,
        TrafficStats stats,
        ZstdNetLogger logger,
        ZstdDictionaryStore dictionaryStore,
        ZstdDictionaryTrainer dictionaryTrainer
    ) {
        this.config = config;
        this.stats = stats;
        this.logger = logger;
        this.dictionaryStore = dictionaryStore;
        this.dictionaryTrainer = dictionaryTrainer;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (mode == Mode.ZSTD) {
            initializeZstdPipeline(ctx, in, out);
            return;
        }
        if (mode == Mode.RAW) {
            out.add(in.readRetainedSlice(in.readableBytes()));
            return;
        }

        if (in.readableBytes() < ZstdFrameCodec.MAGIC.length) {
            return;
        }
        if (startsWithMagic(in)) {
            in.skipBytes(ZstdFrameCodec.MAGIC.length);
            mode = Mode.ZSTD;
            initializeZstdPipeline(ctx, in, out);
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

    private void initializeZstdPipeline(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!streamHeaderRead) {
            if (!ZstdStreamHeader.read(in)) {
                return;
            }
            streamHeaderRead = true;
            countConnection(ctx);
            ZstdNettyPipeline.install(
                ctx.pipeline(),
                config.compressionLevel(),
                false,
                serverStats(),
                ZstdDictionarySession.server(dictionaryStore == null ? null : dictionaryStore.dictionary())
            );
            MinecraftCompressionDisabler.install(ctx.pipeline());
            logger.info("accepted ZstdNet client connection from " + ctx.channel().remoteAddress());
        }
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

            @Override
            public void inboundSample(byte[] raw) {
                if (dictionaryTrainer != null) {
                    dictionaryTrainer.capture(raw);
                }
            }

            @Override
            public void outboundSample(byte[] raw) {
                if (dictionaryTrainer != null) {
                    dictionaryTrainer.capture(raw);
                }
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
