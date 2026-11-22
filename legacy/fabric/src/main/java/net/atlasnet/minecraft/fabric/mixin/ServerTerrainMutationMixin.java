package net.atlasnet.minecraft.fabric.mixin;

import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
abstract class ServerTerrainMutationMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void checkBlockAuthority(BlockPos pos, BlockState state, int flags, int recursion,
                                     CallbackInfoReturnable<Boolean> result) {
        if ((Object) this instanceof ServerLevel && !ShardChunkIO.ownsTerrain(ChunkPos.asLong(pos))) {
            result.setReturnValue(false);
        }
    }

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
            at = @At("HEAD"), cancellable = true)
    private void checkDestroyAuthority(BlockPos pos, boolean drop, Entity source, int recursion,
                                       CallbackInfoReturnable<Boolean> result) {
        // Vanilla emits the break effect and drops items before it calls setBlock.
        if ((Object) this instanceof ServerLevel && !ShardChunkIO.ownsTerrain(ChunkPos.asLong(pos))) {
            result.setReturnValue(false);
        }
    }
}
