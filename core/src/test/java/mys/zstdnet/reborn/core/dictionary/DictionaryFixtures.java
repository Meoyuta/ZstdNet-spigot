package mys.zstdnet.reborn.core.dictionary;
import com.github.luben.zstd.Zstd;
import mys.zstdnet.reborn.core.proxy.ProxyLogger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class DictionaryFixtures {
    public static final ProxyLogger LOGGER = new ProxyLogger() {
        public void info(String message) {}
        public void warn(String message) {}
        public void error(String message) {}
    };
    public static byte[][] samples() {
        byte[][] samples = new byte[300][];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = ("minecraft:stone minecraft:grass_block player inventory chunk biome overworld "
                + i + " " + (i * 7919) + " minecraft:oak_log ").repeat(10).getBytes(StandardCharsets.UTF_8);
        }
        return samples;
    }
    public static ZstdDictionary dictionary() throws Exception {
        byte[] buffer = new byte[4096];
        long size = Zstd.trainFromBuffer(samples(), buffer, false, 3);
        if (Zstd.isError(size)) throw new IllegalStateException(Zstd.getErrorName(size));
        return ZstdDictionary.fromBytes(Arrays.copyOf(buffer, (int) size));
    }
}
