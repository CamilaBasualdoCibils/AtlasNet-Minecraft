package net.atlasnet.minecraft.test.mixin;

import net.atlasnet.minecraft.test.MigrationProbe;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class ClientMotionMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void atlasnet$measure(CallbackInfo callback) {
        MigrationProbe.tick((Minecraft) (Object) this);
    }
}
