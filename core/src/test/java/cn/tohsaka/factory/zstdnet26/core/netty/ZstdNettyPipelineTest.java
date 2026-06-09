package cn.tohsaka.factory.zstdnet26.core.netty;

import cn.tohsaka.factory.zstdnet26.core.protocol.ZstdFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZstdNettyPipelineTest {
    @Test
    void installsBeforeMinecraftEncryptionExists() {
        EmbeddedChannel channel = minecraftLikeChannel();
        ChannelPipeline pipeline = channel.pipeline();

        ZstdNettyPipeline.install(pipeline, 3, true, ZstdFrameStats.NONE);

        List<String> names = pipeline.names();
        assertEquals(names.indexOf("splitter") - 1, names.indexOf(ZstdNettyPipeline.INBOUND_HANDLER));
        assertEquals(names.indexOf("prepender") - 1, names.indexOf(ZstdNettyPipeline.OUTBOUND_HANDLER));
        assertEquals(names.indexOf("packet_handler") - 1, names.indexOf(ZstdNettyPipeline.CONTROL_HANDLER));
    }

    @Test
    void repositionsInsideMinecraftEncryption() {
        EmbeddedChannel channel = minecraftLikeChannel();
        ChannelPipeline pipeline = channel.pipeline();
        ZstdNettyPipeline.install(pipeline, 3, true, ZstdFrameStats.NONE);

        pipeline.addBefore("splitter", "decrypt", new ChannelInboundHandlerAdapter());
        pipeline.addBefore("prepender", "encrypt", new ChannelOutboundHandlerAdapter());
        ZstdNettyPipeline.reposition(pipeline);

        List<String> names = pipeline.names();
        assertEquals(names.indexOf("decrypt") + 1, names.indexOf(ZstdNettyPipeline.INBOUND_HANDLER));
        assertEquals(names.indexOf("encrypt") + 1, names.indexOf(ZstdNettyPipeline.OUTBOUND_HANDLER));
        assertTrue(names.indexOf(ZstdNettyPipeline.OUTBOUND_HANDLER) < names.indexOf("prepender"));
        assertTrue(names.indexOf(ZstdNettyPipeline.INBOUND_HANDLER) < names.indexOf("splitter"));
    }

    @Test
    void encodesMagicAndRoundTripsFrame() {
        byte[] raw = new byte[]{0x05, 0x00, 0x01, 0x02, 0x03, 0x04};
        EmbeddedChannel encoder = new EmbeddedChannel(new ZstdNettyEncoder(3, true, ZstdFrameStats.NONE));

        assertTrue(encoder.writeOutbound(Unpooled.wrappedBuffer(raw)));
        ByteBuf encoded = encoder.readOutbound();
        try {
            for (byte magicByte : ZstdFrameCodec.MAGIC) {
                assertEquals(magicByte, encoded.readByte());
            }

            EmbeddedChannel decoder = new EmbeddedChannel(new ZstdNettyDecoder(ZstdFrameStats.NONE));
            assertTrue(decoder.writeInbound(encoded.retainedSlice()));
            ByteBuf decoded = decoder.readInbound();
            try {
                byte[] actual = new byte[decoded.readableBytes()];
                decoded.readBytes(actual);
                assertArrayEquals(raw, actual);
            } finally {
                decoded.release();
                decoder.finishAndReleaseAll();
            }
        } finally {
            encoded.release();
            encoder.finishAndReleaseAll();
        }
    }

    @Test
    void dropsMinecraftCompressionNegotiation() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast("compress", new ChannelOutboundHandlerAdapter());
        pipeline.addLast("decompress", new ChannelInboundHandlerAdapter());
        pipeline.addLast("packet_handler", new ChannelDuplexHandler());
        MinecraftCompressionDisabler.install(pipeline);

        assertFalse(channel.writeOutbound(new ClientboundLoginCompressionPacket()));
        channel.runPendingTasks();
        channel.runScheduledPendingTasks();

        assertEquals(null, pipeline.get("compress"));
        assertEquals(null, pipeline.get("decompress"));
    }

    private static EmbeddedChannel minecraftLikeChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter());
        return channel;
    }

    private static final class ClientboundLoginCompressionPacket {
    }
}
