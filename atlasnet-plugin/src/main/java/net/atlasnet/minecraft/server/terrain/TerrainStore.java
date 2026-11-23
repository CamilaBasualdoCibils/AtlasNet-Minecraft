package net.atlasnet.minecraft.server.terrain;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Durable storage for vanilla chunk NBT. Calls perform I/O on the caller's thread. */
public interface TerrainStore {
    record ChunkKey(String world, ResourceLocation dimension, int x, int z) {}

    /** Persistence fence installed after AtlasNet grants authority. */
    record Fence(long entityId, long generation) {
        public Fence {
            if (entityId == 0 || generation <= 0) throw new IllegalArgumentException("Invalid chunk authority fence");
        }
    }

    record Snapshot(long revision, CompoundTag data) {}

    record Interaction(UUID id, byte[] response) {
        public Interaction {
            java.util.Objects.requireNonNull(id);
            response = response.clone();
        }

        @Override public byte[] response() { return response.clone(); }
    }

    record Receipt(long revision, Interaction interaction) {}

    /** Carries the source-side state needed to recover an unfinished player effect. */
    record Reservation(ChunkKey chunk, UUID interactionId, CompoundTag sourceState) {
        public Reservation {
            if (sourceState != null) sourceState = sourceState.copy();
        }

        public Reservation(ChunkKey chunk, UUID interactionId) {
            this(chunk, interactionId, null);
        }

        @Override public CompoundTag sourceState() {
            return sourceState == null ? null : sourceState.copy();
        }
    }

    sealed interface CommitResult permits Committed, Conflict, StaleAuthority, ReplayedInteraction {}

    record Committed(long revision) implements CommitResult {}

    record Conflict() implements CommitResult {}

    record StaleAuthority() implements CommitResult {}

    record ReplayedInteraction(Receipt receipt) implements CommitResult {}

    Optional<Fence> loadFence(ChunkKey key) throws IOException;

    /**
     * Compare and replace the persistence fence after an authorized AtlasNet
     * registration or handoff. An empty expected value requires no existing fence.
     */
    boolean advanceFence(ChunkKey key, Optional<Fence> expected, Fence next) throws IOException;

    Optional<Snapshot> load(ChunkKey key) throws IOException;

    Optional<Receipt> loadInteraction(ChunkKey key, UUID id) throws IOException;

    /** Returns the saved reservation, or the earlier request that still needs a result. */
    Reservation reserveInteraction(UUID player, Reservation requested) throws IOException;

    Optional<Reservation> loadReservation(String world, UUID player) throws IOException;

    /** Releases exactly this reservation after the source has handled its durable result. */
    boolean acknowledgeInteraction(UUID player, Reservation reservation) throws IOException;

    /** Records a definitive no-op without advancing the chunk revision. */
    Optional<Receipt> rejectInteraction(ChunkKey key, Fence fence, UUID id) throws IOException;

    /**
     * Writes a snapshot if its base revision is still current. Revision zero
     * creates a previously absent chunk. A committed result is returned only
     * once the database has acknowledged the transaction.
     */
    CommitResult commit(ChunkKey key, Fence fence, long baseRevision, CompoundTag data) throws IOException;

    /** Atomically commits the new terrain snapshot and the interaction result. */
    CommitResult commitInteraction(ChunkKey key, Fence fence, long baseRevision, CompoundTag data,
                                   Interaction interaction) throws IOException;
}
