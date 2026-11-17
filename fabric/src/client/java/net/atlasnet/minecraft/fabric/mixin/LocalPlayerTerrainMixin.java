package net.atlasnet.minecraft.fabric.mixin;

import net.atlasnet.minecraft.fabric.client.AtlasNetPlayConnection;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
abstract class LocalPlayerTerrainMixin {
    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void atlasnet$waitForInitialTerrain(CallbackInfo callback) {
        var connection = AtlasNetPlayConnection.current();
        if (connection != null && connection.waitingForInitialTerrain()) {
            callback.cancel();
        }
    }
}
