package net.atlasnet.minecraft.fabric.mixin;

import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class ServerLevelChunkUnloadMixin {
    @Inject(method = "unload", at = @At("RETURN"))
    private void retireChunk(LevelChunk chunk, CallbackInfo callback) {
        var io = ShardChunkIO.current();
        if (io != null) {
            io.recordLifecycle(chunk, false);
        }
    }
}
