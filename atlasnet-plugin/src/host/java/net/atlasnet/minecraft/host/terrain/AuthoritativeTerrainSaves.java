package net.atlasnet.minecraft.host.terrain;

import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import net.atlasnet.minecraft.server.terrain.AuthoritativeTerrainBackend;
import net.atlasnet.minecraft.server.terrain.TerrainStore;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;

/** Coalesces successful vanilla mutations into one serialized commit per affected chunk. */
public final class AuthoritativeTerrainSaves {
    private final ServerLevel level;
    private final String world;
    private final AuthoritativeTerrainBackend backend;
    private final HashSet<ChunkPos> changed = new HashSet<>();

    public AuthoritativeTerrainSaves(ServerLevel level, String world, AuthoritativeTerrainBackend backend) {
        this.level = level;
        this.world = world;
        this.backend = backend;
    }

    public void changed(ChunkPos position) {
        var server = level.getServer();
        if (!server.isSameThread()) throw new IllegalStateException("Terrain changed off the Minecraft thread");
        boolean schedule = changed.isEmpty();
        changed.add(position);
        if (schedule) server.tell(new TickTask(server.getTickCount(), this::flush));
    }

    private void flush() {
        var batch = new HashSet<>(changed);
        batch.forEach(this::flush);
    }

    /** Drain the queued snapshot before the shard worker freezes authority for this chunk. */
    public CompletableFuture<Void> flush(ChunkPos position) {
        if (!changed.remove(position)) return CompletableFuture.completedFuture(null);
        var cache = level.getChunkSource();
        var key = new TerrainStore.ChunkKey(world, level.dimension().location(), position.x, position.z);
        var chunk = cache.getChunkNow(position.x, position.z);
        if (chunk == null || !backend.maySave(key)) return CompletableFuture.failedFuture(
                new IllegalStateException("Terrain chunk unavailable for save: " + key));
        var data = ChunkSerializer.write(level, chunk);
        return cache.chunkMap.write(position, data).whenComplete((ignored, failure) -> {
            if (failure != null) level.getServer().execute(() -> chunk.setUnsaved(true));
        });
    }

    public CompletableFuture<byte[]> flushInteraction(ChunkPos position, TerrainStore.Interaction interaction) {
        var key = new TerrainStore.ChunkKey(world, level.dimension().location(), position.x, position.z);
        var chunk = level.getChunkSource().getChunkNow(position.x, position.z);
        if (chunk == null || !backend.maySave(key)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Terrain chunk unavailable for save: " + key));
        }
        var data = ChunkSerializer.write(level, chunk);
        changed.remove(position);
        return backend.saveInteraction(key, data, interaction);
    }
}
