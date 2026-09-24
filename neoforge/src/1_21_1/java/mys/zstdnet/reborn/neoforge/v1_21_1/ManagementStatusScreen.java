package mys.zstdnet.reborn.neoforge.v1_21_1;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;

import java.util.Locale;

final class ManagementStatusScreen extends Screen {
    private ManagementStatusPayload payload;

    ManagementStatusScreen(ManagementStatusPayload payload) {
        super(Component.translatable("zstdnet.screen.status.title"));
        this.payload = payload;
    }

    private static MutableComponent bytes(long value) {
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double number = value;
        int unit = 0;
        while (number >= 1024 && unit < units.length - 1) {
            number /= 1024;
            unit++;
        }
        String amount = unit == 0 ? Long.toString(value) : String.format(Locale.ROOT, "%.2f", number);
        return data(amount).append(Component.literal(" " + units[unit]).withStyle(ChatFormatting.GREEN));
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

    void update(ManagementStatusPayload payload) {
        this.payload = payload;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int y = 8;
        graphics.drawString(font, Component.translatable("zstdnet.screen.status.title"), 8, y, 0xFFFFFF, true);
        y += 12;
        y = line(graphics, y, "state", data(Component.translatable("zstdnet.screen.status." + payload.state())));
        y = line(graphics, y, "port", data(Integer.toString(payload.port())));
        y = line(graphics, y, "upload", bytes(payload.wireUpBytes()).append(Component.literal(" / ").withStyle(ChatFormatting.GOLD)).append(bytes(payload.rawUpBytes())));
        y = line(graphics, y, "download", bytes(payload.wireDownBytes()).append(Component.literal(" / ").withStyle(ChatFormatting.GOLD)).append(bytes(payload.rawDownBytes())));
        y = line(graphics, y, "ratio", numberWithUnit(payload.ratioPercent(), "%", "%.2f"));
        y = line(graphics, y, "connections", data(Integer.toString(payload.connections())));
        y = line(graphics, y, "dictionary", data(payload.dictionary()));
        y = line(graphics, y, "dictionary_connections",
                data(Integer.toString(payload.dictionaryConnections())).append(Component.literal(" (").withStyle(ChatFormatting.GOLD))
                        .append(data(Integer.toString(payload.selectedDictionaryConnections())))
                        .append(Component.literal(")").withStyle(ChatFormatting.GOLD)));
    }

    private int line(GuiGraphics graphics, int y, String key, Component value) {
        graphics.drawString(font, Component.translatable("zstdnet.screen.status." + key, value).withStyle(ChatFormatting.GOLD),
                8, y, 0xFFFFFF, true);
        return y + 10;
    }
}
