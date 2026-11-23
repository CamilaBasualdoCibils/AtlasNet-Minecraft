package net.atlasnet.minecraft.host.terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import com.mojang.serialization.Codec;
import net.atlasnet.minecraft.server.terrain.AuthoritativeTerrainBackend;
import net.atlasnet.minecraft.server.terrain.TerrainStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.SavedTick;

/** Applies the committed block palettes to a loaded replica on the Minecraft thread. */
public final class ReplicaTerrainRefresh {
    private record Section(int index, int y, PalettedContainer<BlockState> blocks) {}

    private Codec<PalettedContainer<BlockState>> codec;
    private final WeakHashMap<LevelChunk, Long> applied = new WeakHashMap<>();

    /** Restore the vanilla pending tick queues before the local write fence opens. */
    public void activate(MinecraftServer server, AuthoritativeTerrainBackend backend, TerrainStore.ChunkKey key,
                         TerrainStore.Snapshot snapshot) {
        apply(server, backend, key, snapshot);
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, key.dimension()));
        if (level == null) return;
        var chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
        if (chunk == null) return;
        var position = chunk.getPos();
        var bounds = new BoundingBox(position.getMinBlockX(), level.getMinBuildHeight(), position.getMinBlockZ(),
                position.getMaxBlockX(), level.getMaxBuildHeight() - 1, position.getMaxBlockZ());
        long now = level.getGameTime();
        chunk.unpackTicks(now);
        level.getBlockTicks().clearArea(bounds);
        level.getFluidTicks().clearArea(bounds);
        var data = snapshot.data();
        SavedTick.loadTickList(data.getList("block_ticks", Tag.TAG_COMPOUND),
                name -> BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(name)), position,
                tick -> chunk.getBlockTicks().schedule(tick.unpack(now, level.nextSubTickCount())));
        SavedTick.loadTickList(data.getList("fluid_ticks", Tag.TAG_COMPOUND),
                name -> BuiltInRegistries.FLUID.getOptional(ResourceLocation.parse(name)), position,
                tick -> chunk.getFluidTicks().schedule(tick.unpack(now, level.nextSubTickCount())));
    }

    public void apply(MinecraftServer server, AuthoritativeTerrainBackend backend, TerrainStore.ChunkKey key,
                      TerrainStore.Snapshot snapshot) {
        if (backend.maySave(key)) return;
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, key.dimension()));
        if (level == null) return;
        var cache = level.getChunkSource();
        var chunk = cache.getChunkNow(key.x(), key.z());
        if (chunk == null || applied.getOrDefault(chunk, 0L) >= snapshot.revision()) return;
        if (codec == null) codec = blockCodec();

        var data = snapshot.data();
        if (data.getInt("xPos") != key.x() || data.getInt("zPos") != key.z())
            throw new IllegalArgumentException("Terrain checkpoint coordinates do not match its key");
        var sections = new ArrayList<Section>();
        for (var entry : data.getList("sections", Tag.TAG_COMPOUND)) {
            var section = (net.minecraft.nbt.CompoundTag) entry;
            if (!section.contains("block_states", Tag.TAG_COMPOUND)) continue;
            int y = section.getByte("Y");
            int index = level.getSectionIndexFromSectionY(y);
            if (index < 0 || index >= chunk.getSections().length) continue;
            var blocks = codec.parse(NbtOps.INSTANCE, section.getCompound("block_states")).getOrThrow();
            sections.add(new Section(index, y, blocks));
        }

        // Apply only changed blocks. Calling Level.setBlock here would execute gameplay on the replica.
        var position = new BlockPos.MutableBlockPos();
        for (var update : sections) {
            var target = chunk.getSection(update.index());
            boolean wasEmpty = target.hasOnlyAir();
            for (int y = 0; y < 16; ++y) {
                for (int z = 0; z < 16; ++z) {
                    for (int x = 0; x < 16; ++x) {
                        var state = update.blocks().get(x, y, z);
                        if (state.equals(target.getBlockState(x, y, z))) continue;
                        target.setBlockState(x, y, z, state);
                        int worldY = update.y() * 16 + y;
                        for (var heightmap : chunk.getHeightmaps()) heightmap.getValue().update(x, worldY, z, state);
                        position.set(key.x() * 16 + x, worldY, key.z() * 16 + z);
                        cache.getLightEngine().checkBlock(position.immutable());
                        cache.blockChanged(position);
                    }
                }
            }
            if (wasEmpty != target.hasOnlyAir()) {
                cache.getLightEngine().updateSectionStatus(SectionPos.of(key.x(), update.y(), key.z()),
                        target.hasOnlyAir());
            }
        }
        applyBlockEntities(level, chunk, data);
        applied.put(chunk, snapshot.revision());
    }

    private static void applyBlockEntities(ServerLevel level, LevelChunk chunk, CompoundTag data) {
        var incoming = new HashMap<BlockPos, CompoundTag>();
        for (var entry : data.getList("block_entities", Tag.TAG_COMPOUND)) {
            var tag = (CompoundTag) entry;
            var position = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            var chunkPosition = chunk.getPos();
            if (SectionPos.blockToSectionCoord(position.getX()) == chunkPosition.x
                    && SectionPos.blockToSectionCoord(position.getZ()) == chunkPosition.z
                    && !level.isOutsideBuildHeight(position)) {
                incoming.put(position, tag);
            }
        }

        var pending = pendingBlockEntities(chunk);
        for (var position : chunk.getBlockEntitiesPos()) {
            if (!incoming.containsKey(position)) {
                pending.remove(position);
                chunk.removeBlockEntity(position);
                level.getChunkSource().blockChanged(position);
            }
        }
        incoming.forEach((position, tag) -> {
            var previous = chunk.getBlockEntityNbtForSaving(position, level.registryAccess());
            if (tag.equals(previous)) return;
            pending.remove(position);
            chunk.removeBlockEntity(position);
            var state = chunk.getBlockState(position);
            if (tag.getBoolean("keepPacked") || tag.getString("id").equals("DUMMY")) {
                chunk.setBlockEntityNbt(tag.copy());
            } else {
                var entity = BlockEntity.loadStatic(position, state, tag, level.registryAccess());
                if (entity != null) chunk.addAndRegisterBlockEntity(entity);
            }
            level.getChunkSource().blockChanged(position);
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<BlockPos, CompoundTag> pendingBlockEntities(LevelChunk chunk) {
        try {
            var field = ChunkAccess.class.getDeclaredField("pendingBlockEntities");
            field.setAccessible(true);
            return (Map<BlockPos, CompoundTag>) field.get(chunk);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Vanilla 1.21.1 pending block entities are unavailable", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static Codec<PalettedContainer<BlockState>> blockCodec() {
        try {
            var field = ChunkSerializer.class.getDeclaredField("BLOCK_STATE_CODEC");
            field.setAccessible(true);
            return (Codec<PalettedContainer<BlockState>>) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Vanilla 1.21.1 chunk palette codec is unavailable", failure);
        }
    }
}
