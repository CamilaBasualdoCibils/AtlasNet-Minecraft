package net.atlasnet.minecraft.fabric.mixin;

import net.atlasnet.minecraft.fabric.server.ShardActors;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Projectile.class)
abstract class ShardProjectileOwnerMixin {
    @Inject(method = "getOwner", at = @At("RETURN"), cancellable = true)
    private void atlasnet$firingPlayer(CallbackInfoReturnable<Entity> result) {
        if (result.getReturnValue() == null) {
            var owner = ShardActors.firingPlayer((Projectile) (Object) this);
            if (owner != null) {
                result.setReturnValue(owner);
            }
        }
    }
}
