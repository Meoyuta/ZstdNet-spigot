package mys.zstdnet.reborn.neoforge.v1_21_1;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;

final class DictionaryStatusScreen extends Screen {
    private DictionaryStatusPayload payload;

    DictionaryStatusScreen(DictionaryStatusPayload payload) {
        super(Component.translatable("zstdnet.screen.dictionary_status.title"));
        this.payload = payload;
    }

    private static MutableComponent data(String value) {
        return Component.literal(value).withStyle(ChatFormatting.AQUA);
    }

    private static MutableComponent data(Component value) {
        return value.copy().withStyle(ChatFormatting.AQUA);
    }

    private static MutableComponent numberWithUnit(long value, String unit) {
        return data(Long.toString(value)).append(Component.literal(unit).withStyle(ChatFormatting.GREEN));
    }

    void update(DictionaryStatusPayload payload) {
        this.payload = payload;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int y = 8;
        graphics.drawString(font, Component.translatable("zstdnet.screen.dictionary_status.title"),
                8, y, 0xFFFFFF, true);
        y += 12;
        y = line(graphics, y, "mode", data(Component.translatable(
                "zstdnet.screen.dictionary_status." + payload.mode())));
        y = line(graphics, y, "description", data(payload.description()));
        y = line(graphics, y, "path", data(payload.selectedPath()));
        y = line(graphics, y, "training", data(payload.trainingState()));
        y = line(graphics, y, "samples", data(Integer.toString(payload.sampleCount())));
        y = line(graphics, y, "bytes", data(Integer.toString(payload.sampleBytes()))
                .append(Component.literal(" B").withStyle(ChatFormatting.GREEN)));
        y = line(graphics, y, "remaining", numberWithUnit(payload.remainingSeconds(), " s"));
        if (!payload.available().isEmpty()) {
            y = line(graphics, y, "available", data(String.join(", ", payload.available())));
        }
    }

    private int line(GuiGraphics graphics, int y, String key, Component value) {
        graphics.drawString(font, Component.translatable("zstdnet.screen.dictionary_status." + key, value).withStyle(ChatFormatting.GOLD),
                8, y, 0xFFFFFF, true);
        return y + 10;
    }
}
