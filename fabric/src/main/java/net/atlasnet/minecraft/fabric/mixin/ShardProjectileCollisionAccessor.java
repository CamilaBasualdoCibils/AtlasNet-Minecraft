package net.atlasnet.minecraft.fabric.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Projectile.class)
public interface ShardProjectileCollisionAccessor {
    @Invoker("canHitEntity")
    boolean atlasnet$canHitEntity(Entity target);

    @Invoker("hitTargetOrDeflectSelf")
    ProjectileDeflection atlasnet$hit(HitResult hit);
}
