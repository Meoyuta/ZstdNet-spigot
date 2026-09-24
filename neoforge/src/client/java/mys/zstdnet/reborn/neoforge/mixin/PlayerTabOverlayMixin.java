package mys.zstdnet.reborn.neoforge.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerTabOverlay.class)
abstract class PlayerTabOverlayMixin {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void zstdnet$appendLatency(PlayerInfo playerInfo, CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(cir.getReturnValue().copy().append(
            Component.literal(" [" + playerInfo.getLatency() + "ms]").withStyle(ChatFormatting.GRAY)));
    }
}
