package mys.zstdnet.reborn.neoforge.v1_21_1;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class BenchmarkInfoScreen extends Screen {
    private BenchmarkInfoPayload payload;

    BenchmarkInfoScreen(BenchmarkInfoPayload payload) {
        super(Component.translatable("zstdnet.screen.benchmark.title"));
        this.payload = payload;
    }

    void update(BenchmarkInfoPayload payload) {
        this.payload = payload;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var y = 8;
        graphics.drawString(font, Component.translatable("zstdnet.screen.benchmark.title"),
            8, y, 0xFFFFFF, true);
        y += 12;
        y = drawLine(graphics, y, "state", stateText(payload.state()));
        y = drawLine(graphics, y, "mode", payload.automatic()
            ? data(Component.translatable("zstdnet.screen.benchmark.mode.automatic"))
            : data(Component.translatable("zstdnet.screen.benchmark.mode.manual")));
        y = drawLine(graphics, y, "level", data(Integer.toString(payload.level())));
        y = drawLine(graphics, y, "compression", numberWithUnit(payload.compressionPercent(), "%", "%.2f"));
        y = drawLine(graphics, y, "codec", numberWithUnit(payload.codecMillis(), " ms", "%.3f"));
        y = drawLine(graphics, y, "estimated", numberWithUnit(payload.estimatedMillis(), " ms", "%.3f"));
        y = drawLine(graphics, y, "samples", data(Integer.toString(payload.samples())));
        y = drawLine(graphics, y, "interval", numberWithUnit(payload.intervalMinutes(), " min", "%.0f"));
        drawLine(graphics, y, "completed", data(completedAt(payload.completedAt())));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private int drawLine(GuiGraphics graphics, int y, String key, Component value) {
        graphics.drawString(font, Component.translatable("zstdnet.screen.benchmark." + key, value)
                .withStyle(ChatFormatting.GOLD), 8, y, 0xFFFFFF, true);
        return y + 10;
    }

    private static Component stateText(String state) {
        return data(Component.translatable("zstdnet.screen.benchmark.state." + state));
    }

    private static MutableComponent data(String value) {
        return Component.literal(value).withStyle(ChatFormatting.AQUA);
    }

    private static MutableComponent data(Component value) {
        return value.copy().withStyle(ChatFormatting.AQUA);
    }

    private static MutableComponent numberWithUnit(double value, String unit, String format) {
        if (!Double.isFinite(value)) return data("-");
        return data(String.format(Locale.ROOT, format, value))
                .append(Component.literal(unit).withStyle(ChatFormatting.GREEN));
    }

    private static String completedAt(long timestamp) {
        return timestamp == 0
            ? "-"
            : DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timestamp));
    }
}
