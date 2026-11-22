package net.atlasnet.minecraft.fabric.mixin;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkAccess.class)
public interface ChunkTransferAccessor {
    @Accessor("pendingBlockEntities")
    Map<BlockPos, CompoundTag> atlasnetPendingBlockEntities();
}
