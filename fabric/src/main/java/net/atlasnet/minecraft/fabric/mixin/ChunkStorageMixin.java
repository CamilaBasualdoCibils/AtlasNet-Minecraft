package net.atlasnet.minecraft.fabric.mixin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkStorage.class)
abstract class ChunkStorageMixin {
    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void readTerrain(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> result) {
        var io = terrainIO();
        if (io != null) {
            result.setReturnValue(io.read(pos));
        }
    }

    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void writeTerrain(ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<CompletableFuture<Void>> result) {
        var io = terrainIO();
        if (io != null) {
            result.setReturnValue(io.write(pos, tag));
        }
    }

    @Inject(method = "flushWorker", at = @At("HEAD"))
    private void flushTerrain(CallbackInfo callback) {
        var io = terrainIO();
        if (io != null) {
            io.flush();
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void closeTerrain(CallbackInfo callback) {
        var io = terrainIO();
        if (io != null) {
            io.close();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private ShardChunkIO terrainIO() {
        return (Object) this instanceof ChunkMap ? ShardChunkIO.current() : null;
    }
}
