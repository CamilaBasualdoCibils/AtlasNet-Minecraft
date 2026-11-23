package net.atlasnet.minecraft.host.terrain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.atlasnet.minecraft.server.terrain.TerrainStore;
import net.minecraft.resources.ResourceLocation;
import org.atlasnet.atlasnet.Ids;
import org.atlasnet.atlasnet.Shard;
import org.atlasnet.atlasnet.StateStore;
import org.javagi.base.GErrorException;

/** Delivers committed revision notices through the existing chunk-subscriber set. */
public final class ChunkTerrainReplication {
    private static final String METHOD = "minecraft.terrain.committed.v1";
    private static final System.Logger LOG = System.getLogger(ChunkTerrainReplication.class.getName());

    private record Notice(TerrainStore.ChunkKey key, long revision, int attempts) {
        Notice nextAttempt() {
            return new Notice(key, revision, attempts + 1);
        }
    }

    private static final int MAX_NOTICE_ATTEMPTS = 5;

    @FunctionalInterface
    public interface Apply {
        CompletableFuture<Void> apply(TerrainStore.ChunkKey key, TerrainStore.Snapshot snapshot,
                                      BooleanSupplier current);
    }
    private final Shard shard;
    private final StateStore subscriptions;
    private final TerrainStore store;
    private final UUID localShard;
    private final String world;
    private final Executor io;
    private final ConcurrentHashMap<TerrainStore.ChunkKey, Notice> committed = new ConcurrentHashMap<>();
    // Each instance identifies a request for the current loaded chunk, including across reloads.
    private static final class Refresh {
        final long revision;

        Refresh(long revision) {
            this.revision = revision;
        }
    }

    private final ConcurrentHashMap<TerrainStore.ChunkKey, Refresh> requested = new ConcurrentHashMap<>();
    private final Apply apply;

    public ChunkTerrainReplication(Shard shard, UUID localShard, String world, TerrainStore store,
            Executor io, Apply apply)
            throws GErrorException {
        this.shard = shard;
        this.localShard = localShard;
        this.world = world;
        this.store = store;
        this.io = io;
        this.apply = apply;
        subscriptions = shard.getStateStore("minecraft.chunks");
        shard.onPoll(this::publish);
        if (!shard.bindRpc(METHOD, this::receive))
            throw new IllegalStateException("Could not bind terrain replication ShardRPC");
    }

    /** The persistence callback can enqueue from the database executor. */
    public void committed(TerrainStore.ChunkKey key, long revision) {
        enqueue(new Notice(key, revision, 0));
    }

    /** Runs on the AtlasNet worker. */
    public void loaded(TerrainStore.ChunkKey key, boolean loaded) {
        if (!loaded) {
            requested.remove(key);
            return;
        }
        requestRefresh(key, 0);
    }

    private void enqueue(Notice notice) {
        committed.merge(notice.key(), notice,
                (current, next) -> next.revision() > current.revision() ? next : current);
    }

    private void publish() {
        committed.forEach((key, notice) -> {
            if (!committed.remove(key, notice)) return;
            try {
                var set = subscriptions.getSet(key.dimension() + "/" + key.x() + "/" + key.z() + "/subscribers");
                byte[] payload = encode(notice);
                set.membersAsync(operation -> {
                    var retryScheduled = new AtomicBoolean();
                    try {
                        var members = set.membersFinish(operation);
                        if (members == null) return;
                        for (long i = 0; i < members.nChildren(); i++) {
                            var child = members.getChildValue(i);
                            if (child.getTypeString().equals("v")) child = child.getVariant();
                            var subscriber = UUID.fromString(child.getString(null));
                            if (localShard.equals(subscriber)) continue;
                            shard.callRpcAsync(Ids.shard(subscriber), METHOD, payload, response -> {
                                try {
                                    shard.callRpcFinish(response);
                                } catch (GErrorException | RuntimeException failure) {
                                    if (retryScheduled.compareAndSet(false, true)) retryNotice(notice, failure);
                                }
                            });
                        }
                    } catch (GErrorException | RuntimeException failure) {
                        if (retryScheduled.compareAndSet(false, true)) retryNotice(notice, failure);
                    }
                });
            } catch (GErrorException | IOException | RuntimeException failure) {
                retryNotice(notice, failure);
            }
        });
    }

    private void retryNotice(Notice notice, Exception failure) {
        if (notice.attempts() + 1 >= MAX_NOTICE_ATTEMPTS) {
            log(notice.key(), failure);
            return;
        }
        CompletableFuture.delayedExecutor(1 << notice.attempts(), TimeUnit.SECONDS)
                .execute(() -> enqueue(notice.nextAttempt()));
    }

    private byte[] receive(byte[] payload) {
        try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
            var key = new TerrainStore.ChunkKey(world, ResourceLocation.parse(input.readUTF()),
                    input.readInt(), input.readInt());
            long revision = input.readLong();
            if (input.available() != 0 || revision <= 0) return null;
            var previous = requested.get(key);
            if (previous != null && revision > previous.revision) requestRefresh(key, revision);
            return new byte[0];
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private void requestRefresh(TerrainStore.ChunkKey key, long revision) {
        var request = new Refresh(revision);
        requested.put(key, request);
        refresh(key, request, 1);
    }

    private void refresh(TerrainStore.ChunkKey key, Refresh request, int retrySeconds) {
        io.execute(() -> {
            if (requested.get(key) != request) return;
            try {
                var snapshot = store.load(key).filter(data -> data.revision() >= request.revision);
                if (requested.get(key) != request) return;
                if (snapshot.isPresent()) {
                    apply.apply(key, snapshot.get(), () -> requested.get(key) == request)
                            .whenComplete((ignored, failure) -> {
                                if (failure != null) retry(key, request, retrySeconds, failure);
                            });
                } else if (request.revision > 0) {
                    retry(key, request, retrySeconds, new IOException("Committed revision is unavailable"));
                }
            } catch (IOException | RuntimeException failure) {
                retry(key, request, retrySeconds, failure);
            }
        });
    }

    private void retry(TerrainStore.ChunkKey key, Refresh request, int delay, Throwable failure) {
        if (requested.get(key) != request) return;
        if (delay == 1) LOG.log(System.Logger.Level.WARNING, "Could not refresh committed terrain " + key, failure);
        CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS)
                .execute(() -> {
                    if (requested.get(key) == request) refresh(key, request, Math.min(delay * 2, 30));
                });
    }

    private static byte[] encode(Notice notice) throws IOException {
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            output.writeUTF(notice.key().dimension().toString());
            output.writeInt(notice.key().x());
            output.writeInt(notice.key().z());
            output.writeLong(notice.revision());
            return bytes.toByteArray();
        }
    }

    private static void log(TerrainStore.ChunkKey key, Exception failure) {
        LOG.log(System.Logger.Level.WARNING, "Could not synchronize committed terrain " + key, failure);
    }
}
