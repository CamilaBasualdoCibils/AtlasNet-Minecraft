package net.atlasnet.minecraft.fabric.mixin;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerStatusPinger.class)
abstract class ServerStatusPingerMixin {
    @Inject(method = "pingServer", at = @At("HEAD"), cancellable = true)
    private void skipVanillaStatus(ServerData server, Runnable onPing, Runnable onIcon, CallbackInfo callback) {
        callback.cancel();
    }
}
