package mys.zstdnet.reborn.neoforge.v1_21_1;

import net.minecraft.client.Minecraft;

final class ManagementStatusClientState {
    private ManagementStatusClientState() {
    }

    static void receive(ManagementStatusPayload payload) {
        var client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.screen instanceof ManagementStatusScreen screen) {
                screen.update(payload);
            } else {
                client.setScreen(new ManagementStatusScreen(payload));
            }
        });
    }
}
