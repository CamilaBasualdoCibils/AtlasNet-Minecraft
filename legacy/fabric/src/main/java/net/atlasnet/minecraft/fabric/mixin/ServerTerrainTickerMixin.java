package net.atlasnet.minecraft.fabric.mixin;

import net.atlasnet.minecraft.fabric.server.ShardActors;
import net.atlasnet.minecraft.fabric.server.ShardPlayers;
import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
abstract class ServerTerrainTickerMixin {
    @Inject(method = {"isPositionTickingWithEntitiesLoaded", "shouldTickBlocksAt(J)Z"},
            at = @At("HEAD"), cancellable = true)
    private void checkChunkTickAuthority(long chunk, CallbackInfoReturnable<Boolean> result) {
        if (!ShardChunkIO.ownsTerrain(chunk)) {
            result.setReturnValue(false);
        }
    }

    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void checkRandomTickAuthority(LevelChunk chunk, int tickSpeed, CallbackInfo callback) {
        if (!ShardChunkIO.ownsTerrain(chunk.getPos().toLong())) {
            callback.cancel();
        }
    }

    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void checkEntityTickAuthority(Entity entity, CallbackInfo callback) {
        if (entity instanceof ServerPlayer player ? ShardPlayers.isFrozen(player)
                : ShardActors.managed(entity) ? ShardActors.frozen(entity)
                : !ShardChunkIO.ownsTerrain(entity.chunkPosition().toLong())) {
            callback.cancel();
        }
    }

    @Inject(method = "tickPassenger", at = @At("HEAD"), cancellable = true)
    private void checkPassengerTickAuthority(Entity vehicle, Entity passenger, CallbackInfo callback) {
        if (!(passenger instanceof ServerPlayer)
                && !ShardChunkIO.ownsTerrain(passenger.chunkPosition().toLong())) {
            callback.cancel();
        }
    }
}
