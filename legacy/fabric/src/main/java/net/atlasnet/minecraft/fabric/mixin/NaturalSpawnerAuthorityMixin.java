package net.atlasnet.minecraft.fabric.mixin;

import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NaturalSpawner.class)
abstract class NaturalSpawnerAuthorityMixin {
    @Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
    private static void checkSpawnAuthority(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState state,
                                            boolean friendly, boolean hostile, boolean persistent,
                                            CallbackInfo callback) {
        if (!ShardChunkIO.ownsTerrain(chunk.getPos().toLong())) {
            callback.cancel();
        }
    }
}
