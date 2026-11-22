package net.atlasnet.minecraft.fabric.mixin;

import net.atlasnet.minecraft.fabric.server.ShardActors;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntity.class)
abstract class ShardItemTransferMixin {
    @Inject(method = "isMergable", at = @At("HEAD"), cancellable = true)
    private void atlasnet$skipFrozenMerge(CallbackInfoReturnable<Boolean> result) {
        if (ShardActors.frozen((ItemEntity) (Object) this)) {
            result.setReturnValue(false);
        }
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void atlasnet$skipFrozenDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> result) {
        if (ShardActors.frozen((ItemEntity) (Object) this)) {
            result.setReturnValue(false);
        }
    }
}
