package net.atlasnet.minecraft.server.terrain;

import java.io.IOException;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Durable storage for vanilla chunk NBT. Calls perform I/O on the caller's thread. */
public interface TerrainStore {
    record ChunkKey(String world, ResourceLocation dimension, int x, int z) {}

    record Snapshot(long revision, CompoundTag data) {}

    sealed interface CommitResult permits Committed, Conflict {}

    record Committed(long revision) implements CommitResult {}

    record Conflict() implements CommitResult {}

    Optional<Snapshot> load(ChunkKey key) throws IOException;

    /**
     * Writes a snapshot if its base revision is still current. Revision zero
     * creates a previously absent chunk. A committed result is returned only
     * once the database has acknowledged the transaction.
     */
    CommitResult commit(ChunkKey key, long baseRevision, CompoundTag data) throws IOException;
}
