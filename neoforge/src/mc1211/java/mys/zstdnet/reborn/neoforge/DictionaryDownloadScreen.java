package mys.zstdnet.reborn.neoforge;

import mys.zstdnet.reborn.core.netty.ZstdDictionaryDownloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** All UI mutations run on the client thread; the connection screen continues ticking. */
final class DictionaryDownloadScreen extends ProgressScreen {
    private final Screen previous;

    private DictionaryDownloadScreen(Screen previous) {
        super(false);
        this.previous = previous;
        progressStartNoAbort(Component.literal("Downloading server compression dictionary"));
        progressStage(Component.literal("Receiving dictionary"));
    }

    @Override
    public void tick() {
        previous.tick();
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    @Override
    public void onClose() {
        // Return to Minecraft's connection screen, which owns its Cancel button.
        Minecraft.getInstance().setScreen(previous);
    }

    static ZstdDictionaryDownloadListener listener() {
        return new ZstdDictionaryDownloadListener() {
            private DictionaryDownloadScreen screen;

            @Override
            public void started(long id, int bytes) {
                Minecraft.getInstance().execute(() -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client.screen instanceof ConnectScreen) {
                        screen = new DictionaryDownloadScreen(client.screen);
                        client.setScreen(screen);
                    }
                });
            }

            @Override
            public void progress(int received, int total) {
                Minecraft.getInstance().execute(() -> {
                    if (screen != null) {
                        screen.progressStage(Component.literal("Receiving dictionary: " + received + " / " + total + " bytes"));
                        screen.progressStagePercentage(total == 0 ? 0 : (int) (100L * received / total));
                    }
                });
            }

            @Override
            public void completed(long id) { restore(); }

            @Override
            public void failed(String message) { restore(); }

            private void restore() {
                Minecraft.getInstance().execute(() -> {
                    Minecraft client = Minecraft.getInstance();
                    if (screen != null && client.screen == screen) client.setScreen(screen.previous);
                    screen = null;
                });
            }
        };
    }
}
