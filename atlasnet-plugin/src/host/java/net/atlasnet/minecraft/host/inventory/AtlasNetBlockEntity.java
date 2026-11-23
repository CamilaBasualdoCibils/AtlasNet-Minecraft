package net.atlasnet.minecraft.host.inventory;

import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import net.atlasnet.minecraft.host.ChunkTerrainAuthority;
import net.atlasnet.minecraft.server.terrain.TerrainStore.ChunkKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Addresses vanilla block entities through their existing chunk authority. */
public final class AtlasNetBlockEntity {
    public record Address(ResourceLocation dimension, BlockPos position) {
        public Address {
            position = position.immutable();
        }
    }

    private final String world;
    private final MinecraftServer server;
    private final ChunkTerrainAuthority terrain;

    public AtlasNetBlockEntity(String world, MinecraftServer server, ChunkTerrainAuthority terrain) {
        this.world = world;
        this.server = server;
        this.terrain = terrain;
    }

    /** Resolve an address through the chunk directory on the AtlasNet worker. */
    CompletableFuture<OptionalLong> owner(Address address) {
        return terrain.lookup(key(address));
    }

    /** Resolve the live vanilla object on the game thread under the chunk's active fence. */
    BlockEntity local(long chunkEntity, Address address) {
        if (!server.isSameThread()) throw new IllegalStateException("Block entity lookup requires the server thread");
        var key = key(address);
        if (!terrain.backend().mayAccess(key, chunkEntity)) return null;
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, address.dimension()));
        if (level == null) return null;
        var chunk = level.getChunkSource().getChunkNow(key.x(), key.z());
        if (chunk == null) return null;
        return chunk.getBlockEntity(address.position(), net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK);
    }

    private ChunkKey key(Address address) {
        var chunk = new ChunkPos(address.position());
        return new ChunkKey(world, address.dimension(), chunk.x, chunk.z);
    }
}
