package net.atlasnet.minecraft.fabric.mixin;

import net.atlasnet.minecraft.fabric.server.ShardPlayers;
import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class LevelChunkLifecycleMixin {
    @Inject(method = "setLoaded", at = @At("RETURN"))
    private void recordChunkState(boolean loaded, CallbackInfo callback) {
        var io = ShardChunkIO.current();
        if (io != null && loaded) {
            var chunk = (LevelChunk) (Object) this;
            io.recordLifecycle(chunk, true);
            ShardPlayers.collisionReplicaLoaded(chunk);
        }
    }

    @Inject(method = "isTicking", at = @At("HEAD"), cancellable = true)
    private void checkBlockEntityAuthority(BlockPos pos, CallbackInfoReturnable<Boolean> result) {
        var chunk = (LevelChunk) (Object) this;
        if (chunk.getLevel() instanceof ServerLevel && !ShardChunkIO.ownsTerrain(chunk.getPos().toLong())) {
            result.setReturnValue(false);
        }
    }
}
