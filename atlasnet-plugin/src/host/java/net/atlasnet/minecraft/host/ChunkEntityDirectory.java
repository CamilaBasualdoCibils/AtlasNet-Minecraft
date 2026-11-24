package net.atlasnet.minecraft.host;

import java.util.ArrayDeque;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.atlasnet.minecraft.server.terrain.TerrainStore.ChunkKey;
import org.atlasnet.atlasnet.Shard;
import org.atlasnet.atlasnet.StateStoreValues;
import org.atlasnet.atlasnet.StateStoreWriteCondition;
import org.atlasnet.atlasnet.Transform;
import org.gnome.glib.Variant;
import org.javagi.base.GErrorException;

/** Dimension-aware chunk lookup. AtlasNet's entity registry owns the routing decision. */
public final class ChunkEntityDirectory {
    private final Shard shard;
    private final StateStoreValues values;
    private final ArrayDeque<Runnable> registrations = new ArrayDeque<>();
    private boolean stopping;

    public ChunkEntityDirectory(Shard shard) throws GErrorException {
        this.shard = shard;
        values = shard.getStateStore("minecraft.chunks").getValues();
        shard.onPoll(() -> {
            for (int i = 0; i < 32; i++) {
                var registration = registrations.poll();
                if (registration == null) break;
                registration.run();
            }
        });
        shard.onShutdown(() -> {
            stopping = true;
            for (Runnable registration; (registration = registrations.poll()) != null;) {
                registration.run();
            }
        });
    }

    /** Call from the AtlasNet worker. A reservation is intentionally invisible to lookups. */
    public CompletableFuture<OptionalLong> lookup(ChunkKey chunk) {
        var result = new CompletableFuture<OptionalLong>();
        values.getStringAsync(key(chunk), operation -> {
            try {
                String stored = values.getStringFinish(operation);
                result.complete(stored == null || stored.startsWith("pending/") ?
                        OptionalLong.empty() : OptionalLong.of(Long.parseUnsignedLong(stored)));
            } catch (GErrorException | RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** Race overlapping replicas for one registration. Only the reservation holder creates an entity. */
    public CompletableFuture<OptionalLong> register(ChunkKey chunk) {
        if (stopping) return CompletableFuture.completedFuture(OptionalLong.empty());
        String key = key(chunk);
        String reservation = "pending/" + UUID.randomUUID();
        return put(key, reservation, StateStoreWriteCondition.IF_ABSENT, null).thenComposeAsync(reserved -> {
            if (!reserved) return CompletableFuture.completedFuture(OptionalLong.empty());
            if (stopping) return delete(key, reservation).thenApply(removed -> OptionalLong.empty());
            long entity;
            try {
                entity = shard.registerEntity(center(chunk));
                if (entity == 0) throw new IllegalStateException("AtlasNet did not register chunk " + chunk);
            } catch (GErrorException | RuntimeException failure) {
                return discardRegistration(key, reservation, 0, failure);
            }
            return put(key, Long.toUnsignedString(entity), StateStoreWriteCondition.IF_MATCHES, reservation)
                    .handle((published, failure) -> {
                        if (Boolean.TRUE.equals(published)) {
                            return CompletableFuture.completedFuture(OptionalLong.of(entity));
                        }
                        var cause = failure == null ?
                                new IllegalStateException("Lost registration reservation for " + chunk) : failure;
                        return discardRegistration(key, reservation, entity, cause);
                    }).thenCompose(completion -> completion);
        }, registration -> {
            // A synchronous registration can deliver other StateStore callbacks before returning.
            // During shutdown, run only the reservation cleanup branch above.
            if (stopping) registration.run();
            else registrations.add(registration);
        });
    }

    /** Submit the initial placement through AtlasNet's normal region-transfer routing. */
    public void route(ChunkKey chunk, long entity) throws GErrorException {
        shard.updateEntityTransform(entity, center(chunk));
    }

    private static Transform center(ChunkKey chunk) {
        var position = new Transform();
        position.writeX(chunk.x() * 16.0 + 8);
        position.writeY(0);
        position.writeZ(chunk.z() * 16.0 + 8);
        return position;
    }

    /**
     * Call only after AtlasNet has determined that this entity will retire.
     * A transfer leaves the directory entry intact because it preserves EntityID.
     */
    public CompletableFuture<Boolean> forgetRetired(ChunkKey chunk, long entity) {
        return delete(key(chunk), Long.toUnsignedString(entity));
    }

    private CompletableFuture<OptionalLong> discardRegistration(
            String key, String reservation, long entity, Throwable failure) {
        if (entity != 0) {
            try {
                shard.unregisterEntity(entity);
            } catch (GErrorException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
                return CompletableFuture.failedFuture(failure);
            }
        }
        // A failed acknowledgement may have followed a successful compare-and-set.
        return delete(key, reservation)
                .thenCompose(removed -> !removed && entity != 0 ?
                        delete(key, Long.toUnsignedString(entity)) : CompletableFuture.completedFuture(removed))
                .handle((removed, cleanup) -> {
                    if (cleanup != null) failure.addSuppressed(cleanup);
                    throw new java.util.concurrent.CompletionException(failure);
                });
    }

    private CompletableFuture<Boolean> put(String key, String value, StateStoreWriteCondition condition,
                                           String expected) {
        var result = new CompletableFuture<Boolean>();
        values.putWithOptionsAsync(key, Variant.string(value), 0, condition,
                expected == null ? null : Variant.string(expected), operation -> {
                    try { result.complete(values.putWithOptionsFinish(operation)); }
                    catch (GErrorException | RuntimeException failure) { result.completeExceptionally(failure); }
                });
        return result;
    }

    private CompletableFuture<Boolean> delete(String key, String expected) {
        var result = new CompletableFuture<Boolean>();
        values.deleteWithOptionsAsync(key, StateStoreWriteCondition.IF_MATCHES, Variant.string(expected),
                operation -> {
                    try { result.complete(values.deleteWithOptionsFinish(operation)); }
                    catch (GErrorException | RuntimeException failure) { result.completeExceptionally(failure); }
                });
        return result;
    }

    private static String key(ChunkKey chunk) {
        return "entity/" + chunk.world().length() + "/" + chunk.world() + "/" +
                chunk.dimension() + "/" + chunk.x() + "/" + chunk.z();
    }
}
