package net.atlasnet.minecraft.server.terrain;

import java.io.IOException;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import net.minecraft.nbt.CompoundTag;

/** Connects vanilla chunk I/O to TerrainStore, with write access controlled by AtlasNet callbacks. */
public final class AuthoritativeTerrainBackend implements MinecraftTerrainIO.Backend {
    private static final class Authority {
        final TerrainStore.Fence fence;
        CompletableFuture<Void> writes = CompletableFuture.completedFuture(null);
        CompletableFuture<Void> interactions = CompletableFuture.completedFuture(null);
        volatile long revision;
        CompoundTag unconfirmedSnapshot;
        boolean accepting;

        Authority(TerrainStore.Fence fence) {
            this.fence = fence;
        }
    }

    private final TerrainStore store;
    private final Executor io;
    private final java.util.function.BiConsumer<TerrainStore.ChunkKey, Long> onCommit;
    private final BiFunction<TerrainStore.ChunkKey, TerrainStore.Snapshot, CompletableFuture<Void>> beforeActivation;
    private final HashMap<TerrainStore.ChunkKey, Authority> authorities = new HashMap<>();

    public AuthoritativeTerrainBackend(TerrainStore store, Executor io) {
        this(store, io, (chunk, revision) -> {});
    }

    public AuthoritativeTerrainBackend(TerrainStore store, Executor io,
            java.util.function.BiConsumer<TerrainStore.ChunkKey, Long> onCommit) {
        this(store, io, onCommit, (key, snapshot) -> CompletableFuture.completedFuture(null));
    }

    public AuthoritativeTerrainBackend(TerrainStore store, Executor io,
            java.util.function.BiConsumer<TerrainStore.ChunkKey, Long> onCommit,
            BiFunction<TerrainStore.ChunkKey, TerrainStore.Snapshot, CompletableFuture<Void>> beforeActivation) {
        this.store = store;
        this.io = io;
        this.onCommit = onCommit;
        this.beforeActivation = beforeActivation;
    }

    /** Called after AtlasNet authorizes the chunk entity to acquire the write fence. */
    public synchronized CompletableFuture<Void> activate(TerrainStore.ChunkKey key,
            Optional<TerrainStore.Fence> predecessor, TerrainStore.Fence fence) {
        var current = authorities.get(key);
        if (current != null) {
            return current.fence.equals(fence) && current.accepting ?
                    CompletableFuture.completedFuture(null) :
                    CompletableFuture.failedFuture(new IllegalStateException("Chunk authority already active or transitioning"));
        }
        var authority = new Authority(fence);
        authorities.put(key, authority);
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!store.advanceFence(key, predecessor, fence) &&
                        !store.loadFence(key).equals(Optional.of(fence))) {
                    throw new IOException("Terrain fence was superseded for " + key);
                }
                return store.load(key);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, io).thenCompose(snapshot -> snapshot
                .map(data -> beforeActivation.apply(key, data).thenRun(() -> authority.revision = data.revision()))
                .orElseGet(() -> CompletableFuture.completedFuture(null)))
                .thenRun(() -> {
                    synchronized (this) { authority.accepting = true; }
                }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                synchronized (this) { authorities.remove(key, authority); }
            }
        });
    }

    /** Stop admitting writes and wait for previously accepted writes before a transfer. */
    public synchronized CompletableFuture<Void> freeze(TerrainStore.ChunkKey key, TerrainStore.Fence fence) {
        var authority = authorities.get(key);
        if (authority == null || !authority.fence.equals(fence)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Chunk authority has changed"));
        }
        authority.accepting = false;
        return authority.writes;
    }

    public synchronized void resume(TerrainStore.ChunkKey key, TerrainStore.Fence fence) {
        var authority = authorities.get(key);
        if (authority != null && authority.fence.equals(fence)) authority.accepting = true;
    }

    public synchronized boolean release(TerrainStore.ChunkKey key, TerrainStore.Fence fence) {
        var authority = authorities.get(key);
        if (authority == null) return true;
        if (!authority.fence.equals(fence) || authority.accepting || !authority.writes.isDone()
                || authority.writes.isCompletedExceptionally()) return false;
        return authorities.remove(key, authority);
    }

    @Override
    public CompletableFuture<Optional<CompoundTag>> load(TerrainStore.ChunkKey key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.load(key).map(TerrainStore.Snapshot::data);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, io);
    }

    public synchronized boolean needsInitialSnapshot(TerrainStore.ChunkKey key) {
        var authority = authorities.get(key);
        return authority != null && authority.accepting && authority.revision == 0;
    }

    @Override
    public synchronized boolean maySave(TerrainStore.ChunkKey key) {
        var authority = authorities.get(key);
        return authority != null && authority.accepting;
    }

    public synchronized boolean mayAccess(TerrainStore.ChunkKey key, long chunkEntity) {
        var authority = authorities.get(key);
        return authority != null && authority.accepting && authority.fence.entityId() == chunkEntity;
    }

    @Override
    public synchronized CompletableFuture<Void> save(TerrainStore.ChunkKey key, CompoundTag snapshot) {
        var authority = authorities.get(key);
        if (authority == null || !authority.accepting) {
            return CompletableFuture.failedFuture(new IllegalStateException("No active chunk write authority for " + key));
        }
        authority.writes = afterWrites(authority)
                .thenRunAsync(() -> commit(key, authority, snapshot), io);
        return authority.writes;
    }

    public CompletableFuture<Optional<TerrainStore.Receipt>> loadInteraction(
            TerrainStore.ChunkKey key, java.util.UUID id) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.loadInteraction(key, id);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, io);
    }

    public CompletableFuture<TerrainStore.Reservation> reserveInteraction(java.util.UUID player,
                                                                           TerrainStore.Reservation requested) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.reserveInteraction(player, requested);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, io);
    }

    public CompletableFuture<Optional<TerrainStore.Reservation>> loadReservation(String world, java.util.UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.loadReservation(world, player);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, io);
    }

    public CompletableFuture<Boolean> acknowledgeInteraction(java.util.UUID player,
                                                             TerrainStore.Reservation reservation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.acknowledgeInteraction(player, reservation);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, io);
    }

    public synchronized CompletableFuture<byte[]> rejectInteraction(TerrainStore.ChunkKey key, java.util.UUID id) {
        var authority = authorities.get(key);
        if (authority == null || !authority.accepting) return CompletableFuture.completedFuture(null);
        // A rejection cannot overtake an older write or move to a different write fence.
        var result = afterWrites(authority).thenApplyAsync(ignored -> {
            try {
                return store.rejectInteraction(key, authority.fence, id)
                        .map(receipt -> acceptReceipt(key, authority, receipt)).orElse(null);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, io);
        authority.writes = result.thenAccept(ignored -> {});
        return result;
    }

    /** Uses the same fenced write sequence as vanilla saves. */
    public synchronized CompletableFuture<byte[]> saveInteraction(TerrainStore.ChunkKey key, CompoundTag snapshot,
                                                                    TerrainStore.Interaction interaction) {
        var authority = authorities.get(key);
        if (authority == null || !authority.accepting) {
            return CompletableFuture.failedFuture(new IllegalStateException("No active chunk write authority for " + key));
        }
        var result = afterWrites(authority)
                .thenCompose(ignored -> retryInteraction(key, authority, snapshot, interaction, 1, io));
        authority.interactions = result.thenAccept(ignored -> {});
        authority.writes = authority.interactions;
        return result;
    }

    private static CompletableFuture<Void> afterWrites(Authority authority) {
        var previous = authority.writes;
        return authority.interactions.thenCompose(ignored -> previous.handle((value, failure) -> null));
    }

    private CompletableFuture<byte[]> retryInteraction(TerrainStore.ChunkKey key, Authority authority,
            CompoundTag snapshot, TerrainStore.Interaction interaction, int delay, Executor executor) {
        return CompletableFuture.supplyAsync(
                () -> commitInteraction(key, authority, snapshot, interaction), executor)
                .exceptionallyCompose(failure -> {
                    if (!(failure.getCause() instanceof IOException)) return CompletableFuture.failedFuture(failure);
                    var retry = CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.SECONDS, io);
                    return retryInteraction(key, authority, snapshot, interaction, Math.min(delay * 2, 30), retry);
                });
    }

    private byte[] commitInteraction(TerrainStore.ChunkKey key, Authority authority, CompoundTag snapshot,
                                     TerrainStore.Interaction interaction) {
        try {
            reconcileCommit(key, authority);
            var result = store.commitInteraction(key, authority.fence, authority.revision, snapshot, interaction);
            if (result instanceof TerrainStore.Committed committed) {
                authority.revision = committed.revision();
                onCommit.accept(key, committed.revision());
                return interaction.response();
            }
            if (result instanceof TerrainStore.ReplayedInteraction replayed) {
                return acceptReceipt(key, authority, replayed.receipt());
            }
            throw new IllegalStateException("Terrain revision or write authority was superseded for " + key);
        } catch (IOException failure) {
            // The connection may have failed after PostgreSQL made the commit durable.
            try {
                var receipt = store.loadInteraction(key, interaction.id());
                if (receipt.isPresent()) {
                    return acceptReceipt(key, authority, receipt.get());
                }
            } catch (IOException lookupFailure) {
                failure.addSuppressed(lookupFailure);
            }
            throw new CompletionException(failure);
        }
    }

    private byte[] acceptReceipt(TerrainStore.ChunkKey key, Authority authority, TerrainStore.Receipt saved) {
        if (saved.revision() > authority.revision) {
            authority.revision = saved.revision();
            onCommit.accept(key, saved.revision());
        }
        return saved.interaction().response();
    }

    private void commit(TerrainStore.ChunkKey key, Authority authority, CompoundTag snapshot) {
        try {
            var recovered = reconcileCommit(key, authority);
            if (snapshot.equals(recovered)) return;
            TerrainStore.CommitResult result;
            try {
                result = store.commit(key, authority.fence, authority.revision, snapshot);
            } catch (IOException failure) {
                authority.unconfirmedSnapshot = snapshot.copy();
                throw failure;
            }
            if (!(result instanceof TerrainStore.Committed written)) {
                throw new IOException("Terrain revision or write authority was superseded for " + key);
            }
            authority.revision = written.revision();
            onCommit.accept(key, written.revision());
        } catch (IOException failure) {
            throw new CompletionException(failure);
        }
    }

    /** A lost SQL reply may hide a successful write. Reconcile before using its revision. */
    private CompoundTag reconcileCommit(TerrainStore.ChunkKey key, Authority authority) throws IOException {
        var unconfirmed = authority.unconfirmedSnapshot;
        if (unconfirmed == null) return null;
        var persisted = store.load(key);
        long revision = persisted.map(TerrainStore.Snapshot::revision).orElse(0L);
        if (revision == authority.revision) {
            authority.unconfirmedSnapshot = null;
            return null;
        }
        if (revision != Math.addExact(authority.revision, 1)
                || !persisted.orElseThrow().data().equals(unconfirmed)
                || !store.loadFence(key).equals(Optional.of(authority.fence))) {
            throw new IOException("Could not reconcile terrain revision for " + key);
        }
        authority.revision = revision;
        authority.unconfirmedSnapshot = null;
        onCommit.accept(key, revision);
        return unconfirmed;
    }
}
