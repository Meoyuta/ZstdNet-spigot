package mys.zstdnet.reborn.neoforge.v1_21_11;

import net.minecraft.client.Minecraft;

final class BenchmarkInfoClientState {
    private static volatile BenchmarkInfoPayload latest;

    private BenchmarkInfoClientState() {
    }

    static void receive(BenchmarkInfoPayload payload) {
        latest = payload;
        var client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.screen instanceof BenchmarkInfoScreen screen) {
                screen.update(payload);
            } else {
                client.setScreen(new BenchmarkInfoScreen(payload));
            }
        });
    }

    static BenchmarkInfoPayload latest() {
        return latest;
    }
}
