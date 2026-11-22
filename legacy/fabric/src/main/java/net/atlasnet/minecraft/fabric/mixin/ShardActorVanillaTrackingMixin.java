package net.atlasnet.minecraft.fabric.mixin;

import net.atlasnet.minecraft.fabric.server.ShardActors;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class ShardActorVanillaTrackingMixin {
    @Shadow @Final private Entity entity;

    @Inject(method = "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At("HEAD"), cancellable = true)
    private void atlasnet$trackThroughAtlasNet(ServerPlayer player, CallbackInfo callback) {
        if ((entity instanceof ServerPlayer && Boolean.getBoolean("atlasnet.minecraft.shard"))
                || (ShardActors.managed(entity) && ShardActors.usesAtlasNetVisuals(entity))) {
            callback.cancel();
        }
    }
}
