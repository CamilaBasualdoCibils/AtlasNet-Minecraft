package net.atlasnet.minecraft.fabric.server.storage;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import net.atlasnet.minecraft.fabric.mixin.ChunkTransferAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.ticks.LevelChunkTicks;

final class ChunkStateInstaller {
    private ChunkStateInstaller() {}

    /** Replace the replica in place so Vanilla's chunk holders and tick references stay valid. */
    static CompletableFuture<?> install(ServerLevel level, LevelChunk target, CompoundTag data) {
        var cache = level.getChunkSource();
        var decoded = ChunkSerializer.read(level, cache.getPoiManager(),
                new RegionStorageInfo("atlasnet", level.dimension(), "chunk"), target.getPos(), data);
        if (!(decoded instanceof ImposterProtoChunk saved)) {
            throw new IllegalStateException("Expected a full checkpoint for " + target.getPos());
        }
        LevelChunk snapshot = saved.getWrapped();
        snapshot.runPostLoad();
        snapshot.unpackTicks(level.getGameTime());

        for (var pos : new ArrayList<>(target.getBlockEntities().keySet())) {
            target.removeBlockEntity(pos);
        }
        target.clearAllBlockEntities();
        ((ChunkTransferAccessor) target).atlasnetPendingBlockEntities().clear();
        System.arraycopy(snapshot.getSections(), 0, target.getSections(), 0, target.getSections().length);
        System.arraycopy(snapshot.getPostProcessing(), 0, target.getPostProcessing(), 0,
                target.getPostProcessing().length);
        for (var entry : snapshot.getHeightmaps()) {
            target.setHeightmap(entry.getKey(), entry.getValue().getRawData());
        }
        target.setAllStarts(snapshot.getAllStarts());
        target.setAllReferences(snapshot.getAllReferences());
        target.setInhabitedTime(snapshot.getInhabitedTime());
        target.setBlendingData(snapshot.getBlendingData());
        for (var pos : snapshot.getBlockEntitiesPos()) {
            var tag = snapshot.getBlockEntityNbtForSaving(pos, level.registryAccess());
            if (tag != null) {
                target.setBlockEntityNbt(tag);
                target.getBlockEntity(pos);
            }
        }
        target.unregisterTickContainerFromLevel(level);
        target.unpackTicks(level.getGameTime());
        replaceBlockTicks(target, snapshot);
        replaceFluidTicks(target, snapshot);
        target.registerTickContainerInLevel(level);

        var light = cache.getLightEngine();
        target.initializeLightSources();
        target.setLightCorrect(false);
        boolean savedLight = snapshot.isLightCorrect();
        return light.initializeLight(target, savedLight).thenCompose(chunk -> light.lightChunk(chunk, savedLight));
    }

    private static void replaceBlockTicks(LevelChunk target, LevelChunk snapshot) {
        var destination = (LevelChunkTicks<net.minecraft.world.level.block.Block>) target.getBlockTicks();
        var source = (LevelChunkTicks<net.minecraft.world.level.block.Block>) snapshot.getBlockTicks();
        destination.removeIf(tick -> true);
        source.getAll().forEach(destination::schedule);
    }

    private static void replaceFluidTicks(LevelChunk target, LevelChunk snapshot) {
        var destination = (LevelChunkTicks<net.minecraft.world.level.material.Fluid>) target.getFluidTicks();
        var source = (LevelChunkTicks<net.minecraft.world.level.material.Fluid>) snapshot.getFluidTicks();
        destination.removeIf(tick -> true);
        source.getAll().forEach(destination::schedule);
    }
}
