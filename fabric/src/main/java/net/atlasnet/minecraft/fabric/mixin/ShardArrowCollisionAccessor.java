package net.atlasnet.minecraft.fabric.mixin;

import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractArrow.class)
public interface ShardArrowCollisionAccessor {
    @Invoker("canHitEntity")
    boolean atlasnet$canHitEntity(Entity target);

    @Invoker("onHitEntity")
    void atlasnet$hitEntity(EntityHitResult hit);
}
