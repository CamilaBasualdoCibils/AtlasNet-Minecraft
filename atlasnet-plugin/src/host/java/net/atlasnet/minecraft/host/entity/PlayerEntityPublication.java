package net.atlasnet.minecraft.host.entity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.Duration;
import net.minecraft.world.entity.Entity;
import org.atlasnet.atlasnet.Shard;
import org.javagi.base.GErrorException;

/** Coalesces active-entity snapshots and serializes checkpoint writes per entity. */
public final class PlayerEntityPublication {
    private static final System.Logger LOG = System.getLogger(PlayerEntityPublication.class.getName());

    private static final long WRITE_RETRY_NANOS = java.time.Duration.ofMillis(250).toNanos();

    private record Change(AtlasNetEntityStore.Snapshot snapshot, boolean retired, boolean deleted, Runnable afterRetire) {}

    private static final class Publication {
        AtlasNetEntityStore.Published last;
        AtlasNetEntityStore.Snapshot desired;
        boolean busy;
        boolean frozen;
        boolean retired;
        boolean deleted;
        boolean lostOwnership;
        long checkpointedRevision;
        long checkpointDue;
        long retryDue;
        volatile long durableGeneration;
        boolean checkpointInFlight;
        boolean checkpointedDeletion;
        boolean checkpointFailed;
        Runnable afterRetire;
    }

    private final AtlasNetEntityStore store;
    private final PostgresEntityCheckpoints checkpoints;
    private final long checkpointInterval;
    private final ExecutorService checkpointIo = Executors.newSingleThreadExecutor();
    private final ExecutorService restoreIo = Executors.newSingleThreadExecutor();
    private final CompletableFuture<java.util.Set<PostgresEntityCheckpoints.CheckpointChunk>> recoveryChunks;
    private final ConcurrentHashMap<Long, Runnable> checkpointCompletions = new ConcurrentHashMap<>();
    // Coalesce game-thread samples before handing them to the worker.
    private final ConcurrentHashMap<Long, Change> changes = new ConcurrentHashMap<>();
    private final HashMap<Long, Publication> players = new HashMap<>();
    private final HashSet<Long> retrying = new HashSet<>();
    private final HashSet<Long> adopting = new HashSet<>();
    private boolean stopping;

    public PlayerEntityPublication(Shard shard, PostgresEntityCheckpoints checkpoints,
                                   Duration interval) throws GErrorException {
        store = new AtlasNetEntityStore(shard, id -> {
            var publication = players.get(id);
            return adopting.contains(id) || publication != null && !publication.frozen && !publication.lostOwnership;
        });
        this.checkpoints = checkpoints;
        recoveryChunks = CompletableFuture.supplyAsync(() -> {
            try { return checkpoints.occupiedChunks(); }
            catch (java.sql.SQLException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, restoreIo);
        checkpointInterval = interval.toNanos();
        shard.onPoll(this::poll);
        shard.onPollEvents(() -> {
            if (stopping) poll();
            else finishCheckpoints();
        });
        shard.onShutdown(this::shutdown);
    }

    java.util.concurrent.CompletableFuture<java.util.Optional<PostgresEntityCheckpoints.Checkpoint>>
    loadCheckpoint(java.util.UUID uuid) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return checkpoints.load(uuid); }
            catch (java.sql.SQLException | java.io.IOException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, restoreIo);
    }

    CompletableFuture<java.util.List<AtlasNetEntityStore.Snapshot>> loadCheckpointChunk(
            String dimension, net.minecraft.world.level.ChunkPos chunk) {
        return recoveryChunks.thenCompose(chunks -> {
            if (!chunks.contains(new PostgresEntityCheckpoints.CheckpointChunk(dimension, chunk.x, chunk.z)))
                return CompletableFuture.completedFuture(java.util.List.of());
            return CompletableFuture.supplyAsync(() -> {
                try { return checkpoints.loadChunk(dimension, chunk); }
                catch (java.sql.SQLException | java.io.IOException failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }, restoreIo);
        });
    }

    public void capture(long entity, Entity player) {
        try {
            changes.put(entity, new Change(AtlasNetEntityStore.Snapshot.capture(entity, 1, 1, player), false, false, null));
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not capture Minecraft entity state", failure);
        }
    }

    public void retire(long entity) {
        retire(entity, false, null);
    }

    void retire(long entity, boolean deleted, Runnable afterRetire) {
        changes.compute(entity, (id, current) -> new Change(
                current == null ? null : current.snapshot(), true, deleted, afterRetire));
    }

    /** Freeze after the game thread has stopped ticking. Retry preparation if an earlier write is still running. */
    AtlasNetEntityStore.Snapshot freeze(long entity, AtlasNetEntityStore.Snapshot finalState) {
        var publication = players.get(entity);
        if (publication == null || publication.last == null || publication.busy ||
                publication.checkpointInFlight || publication.retryDue != 0 || publication.retired || publication.lostOwnership)
            return null;
        publication.frozen = true;
        changes.remove(entity);
        var last = publication.last.snapshot();
        return new AtlasNetEntityStore.Snapshot(entity, Math.addExact(last.ownershipGeneration(), 1),
                Math.addExact(last.revision(), 1), finalState.minecraftId(),
                finalState.entityType(), finalState.dimension(), finalState.data());
    }

    void abortFreeze(long entity) {
        var publication = players.get(entity);
        if (publication != null) publication.frozen = false;
    }

    void commitFreeze(long entity) {
        players.remove(entity);
        changes.remove(entity);
    }

    /** Install the transferred revision before activation, using the previous snapshot as the CAS fence. */
    CompletableFuture<Boolean> adopt(AtlasNetEntityStore.Snapshot next) {
        long id = next.entityId();
        if (!adopting.add(id)) return CompletableFuture.completedFuture(false);
        return store.load(id).thenCompose(current -> {
            var previous = current.orElseThrow();
            var last = previous.snapshot();
            if (last.ownershipGeneration() == next.ownershipGeneration() &&
                    last.revision() == next.revision() && last.equals(next)) {
                install(previous);
                return CompletableFuture.completedFuture(true);
            }
            if (last.ownershipGeneration() != next.ownershipGeneration() - 1 ||
                    last.revision() != next.revision() - 1)
                return CompletableFuture.completedFuture(false);
            return store.publish(previous, next).thenApply(written -> {
                written.ifPresent(this::install);
                return written.isPresent();
            });
        }).whenComplete((result, failure) -> adopting.remove(id));
    }

    private void install(AtlasNetEntityStore.Published last) {
        var publication = new Publication();
        publication.last = last;
        publication.checkpointDue = System.nanoTime();
        players.put(last.snapshot().entityId(), publication);
    }

    private void finishCheckpoints() {
        checkpointCompletions.forEach((entity, complete) -> {
            if (checkpointCompletions.remove(entity, complete)) complete.run();
        });
    }

    private void poll() {
        finishCheckpoints();
        changes.forEach((entity, change) -> {
            if (!changes.remove(entity, change)) return;
            var publication = players.computeIfAbsent(entity, ignored -> new Publication());
            publication.retired = change.retired();
            publication.deleted = change.deleted();
            publication.afterRetire = change.afterRetire();
            if (change.snapshot() != null) publication.desired = change.snapshot();
            advance(entity, publication);
        });
        long now = System.nanoTime();
        for (long entity : java.util.List.copyOf(retrying)) {
            var publication = players.get(entity);
            if (publication == null) retrying.remove(entity);
            else if (now - publication.retryDue >= 0) {
                retrying.remove(entity);
                publication.retryDue = 0;
                advance(entity, publication);
            }
        }
        players.forEach(this::scheduleCheckpoint);
    }

    private void shutdown() {
        stopping = true;
        for (var publication : players.values()) {
            publication.checkpointDue = System.nanoTime();
        }
        poll();
    }

    private void scheduleCheckpoint(long entity, Publication publication) {
        if (publication.frozen || publication.lostOwnership || publication.checkpointInFlight) return;
        var snapshot = checkpointSnapshot(entity, publication);
        boolean deleted = publication.deleted;
        if (!needsCheckpoint(publication, snapshot) ||
                ((!publication.retired || publication.checkpointFailed) &&
                        System.nanoTime() < publication.checkpointDue)) return;
        publication.checkpointInFlight = true;
        checkpointIo.execute(() -> {
            boolean written = false;
            try {
                if (publication.durableGeneration == 0) publication.durableGeneration = checkpoints.claim(snapshot);
                written = checkpoints.checkpointClaimed(snapshot, publication.durableGeneration,
                        Math.addExact(snapshot.revision(), deleted ? 2 : 1), deleted);
            } catch (Exception failure) {
                logFailure(entity, failure);
            }
            boolean saved = written;
            checkpointCompletions.put(entity, () -> {
                publication.checkpointInFlight = false;
                publication.checkpointFailed = !saved;
                if (saved) {
                    publication.checkpointedRevision = snapshot.revision();
                    publication.checkpointedDeletion = deleted;
                }
                publication.checkpointDue = System.nanoTime() + checkpointInterval;
                if (publication.retired) advance(entity, publication);
            });
        });
    }

    private AtlasNetEntityStore.Snapshot checkpointSnapshot(long entity, Publication publication) {
        var last = publication.last == null ? null : publication.last.snapshot();
        var finalSample = publication.desired;
        if (!stopping || !publication.retired || !differs(last, finalSample)) return last;
        // The node can stop servicing StateStore requests during whole-node shutdown.
        // The final local sample can still be committed to PostgreSQL independently.
        long revision = last == null ? 1 : Math.addExact(last.revision(), 1);
        long generation = last == null ? 1 : last.ownershipGeneration();
        return new AtlasNetEntityStore.Snapshot(entity, generation, revision, finalSample.minecraftId(),
                finalSample.entityType(), finalSample.dimension(), finalSample.data());
    }

    private static boolean needsCheckpoint(Publication publication, AtlasNetEntityStore.Snapshot snapshot) {
        return snapshot != null && !publication.lostOwnership &&
                (snapshot.revision() > publication.checkpointedRevision ||
                        publication.deleted && !publication.checkpointedDeletion);
    }

    private static boolean differs(AtlasNetEntityStore.Snapshot last, AtlasNetEntityStore.Snapshot sample) {
        return sample != null && (last == null || !last.dimension().equals(sample.dimension()) ||
                !last.entityType().equals(sample.entityType()) || !last.data().equals(sample.data()));
    }

    private void advance(long entity, Publication publication) {
        if (publication.busy || publication.frozen || publication.retryDue != 0) return;
        var sample = publication.desired;
        var last = publication.last == null ? null : publication.last.snapshot();
        boolean changed = differs(last, sample);
        if (publication.retired && (!changed || publication.lostOwnership)) {
            if (publication.checkpointInFlight) return;
            if (needsCheckpoint(publication, last)) {
                scheduleCheckpoint(entity, publication);
                return;
            }
            if (publication.last != null && !publication.lostOwnership) {
                publication.busy = true;
                store.retire(publication.last).whenComplete((removed, failure) -> {
                    if (failure != null) {
                        publication.busy = false;
                        retryWrite(entity, publication, failure);
                        return;
                    }
                    players.remove(entity, publication);
                    if (publication.afterRetire != null) publication.afterRetire.run();
                });
            } else {
                players.remove(entity, publication);
                if (publication.afterRetire != null) publication.afterRetire.run();
            }
            return;
        }
        if (!changed || publication.lostOwnership) return;
        var next = new AtlasNetEntityStore.Snapshot(entity, last == null ? 1 : last.ownershipGeneration(),
                last == null ? 1 : Math.addExact(last.revision(), 1),
                sample.minecraftId(), sample.entityType(), sample.dimension(), sample.data());
        publication.busy = true;
        store.publish(publication.last, next).whenComplete((written, failure) -> {
            publication.busy = false;
            if (failure != null) {
                retryWrite(entity, publication, failure);
                return;
            } else if (written.isEmpty()) publication.lostOwnership = true;
            else {
                if (publication.last == null) publication.checkpointDue = System.nanoTime() + checkpointInterval;
                publication.last = written.get();
            }
            if (publication.retired || (failure == null && publication.desired != sample)) {
                advance(entity, publication);
            }
        });
    }

    private void retryWrite(long entity, Publication publication, Throwable failure) {
        logFailure(entity, failure);
        publication.retryDue = System.nanoTime() + WRITE_RETRY_NANOS;
        retrying.add(entity);
    }

    private static void logFailure(long entity, Throwable failure) {
        LOG.log(System.Logger.Level.WARNING, "Could not update entity snapshot " + Long.toUnsignedString(entity), failure);
    }
}
