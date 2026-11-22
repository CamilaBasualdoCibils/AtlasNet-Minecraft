package net.atlasnet.minecraft.fabric.server.storage;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** Blocking storage operations for use by Minecraft's chunk I/O executor. */
public interface ChunkStorage {
    /** Call only for committed authority. Epochs must increase across handoffs and restarts. */
    boolean claim(ChunkKey key, Authority authority) throws IOException;

    Optional<StoredChunk> load(ChunkKey key, Authority authority) throws IOException;

    /** Version 0 creates a new chunk. A false result requires refreshing authority or state. */
    boolean save(ChunkKey key, Authority authority, long expectedVersion, CompoundTag data) throws IOException;

    /** Save a frozen chunk and reserve its next revision for this AtlasNet transfer ID. */
    boolean prepareHandoff(ChunkKey key, UUID transferId, Authority source,
                           long expectedVersion, CompoundTag data) throws IOException;

    /** Read the exact checkpoint while it is reserved for this transfer. */
    Optional<StoredChunk> loadPreparedHandoff(ChunkKey key, UUID transferId, Authority source,
                                             long savedVersion) throws IOException;

    /** Release a prepared checkpoint without changing its revision or the source's write fence. */
    boolean abortHandoff(ChunkKey key, UUID transferId, Authority source, long savedVersion) throws IOException;

    /**
     * Move the write fence for a saved checkpoint. Call only after AtlasNet decides to commit.
     * A retry of the same transfer is safe even if the destination has since saved newer data.
     */
    boolean commitHandoff(ChunkKey key, UUID transferId, Authority source,
                          Authority destination, long savedVersion) throws IOException;

    record Authority(long epoch, UUID owner) {
        public Authority {
            if (epoch <= 0) {
                throw new IllegalArgumentException("Authority epoch must be positive");
            }
            Objects.requireNonNull(owner, "owner");
        }
    }

    record StoredChunk(long version, CompoundTag data) {}
}
