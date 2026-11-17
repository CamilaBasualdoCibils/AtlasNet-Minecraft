package net.atlasnet.minecraft.fabric.mixin;

import net.atlasnet.minecraft.fabric.client.AtlasNetPlayConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
abstract class LocalPlayerGameModeMixin {
    @Inject(method = "isSpectator", at = @At("HEAD"), cancellable = true)
    private void atlasnet$checkSpectator(CallbackInfoReturnable<Boolean> result) {
        var mode = atlasnet$mode();
        if (mode != null) {
            result.setReturnValue(mode == GameType.SPECTATOR);
        }
    }

    @Inject(method = "isCreative", at = @At("HEAD"), cancellable = true)
    private void atlasnet$checkCreative(CallbackInfoReturnable<Boolean> result) {
        var mode = atlasnet$mode();
        if (mode != null) {
            result.setReturnValue(mode == GameType.CREATIVE);
        }
    }

    @Unique
    private GameType atlasnet$mode() {
        if ((Object) this instanceof LocalPlayer && AtlasNetPlayConnection.current() != null) {
            var mode = Minecraft.getInstance().gameMode;
            return mode == null ? null : mode.getPlayerMode();
        }
        return null;
    }
}
