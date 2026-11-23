package net.atlasnet.minecraft.host.entity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.Entity;
import org.atlasnet.atlasnet.Ids;
import org.atlasnet.atlasnet.Shard;
import org.atlasnet.atlasnet.StateStoreValues;
import org.atlasnet.atlasnet.StateStoreWriteCondition;
import org.gnome.glib.Variant;
import org.gnome.glib.VariantType;
import org.javagi.base.GErrorException;

/** Reads and conditionally publishes whole entity snapshots on the AtlasNet worker thread. */
public final class AtlasNetEntityStore {
    private static final long MAX_NBT_BYTES = 16L * 1024 * 1024;

    /** A detached snapshot. Its NBT can be read by a non-owning worker. */
    public record Snapshot(long entityId, long ownershipGeneration, long revision,
                           UUID minecraftId, String entityType, String dimension, CompoundTag data) {
        public Snapshot {
            data = data.copy();
        }

        @Override
        public CompoundTag data() {
            return data.copy();
        }

        /** Capture on the Minecraft server thread while this worker owns the entity. */
        public static Snapshot capture(long entityId, long generation, long revision, Entity entity) {
            return new Snapshot(entityId, generation, revision, entity.getUUID(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    entity.level().dimension().location().toString(), entity.saveWithoutId(new CompoundTag()));
        }
    }

    /** Includes the exact serialized bytes used for subsequent atomic comparison. */
    public static final class Published {
        private final Snapshot snapshot;
        private final byte[] encoded;

        private Published(Snapshot snapshot, byte[] encoded) {
            this.snapshot = snapshot;
            this.encoded = encoded;
        }

        public Snapshot snapshot() {
            return snapshot;
        }
    }

    private final StateStoreValues values;
    private final LongPredicate canWrite;

    /** Opens the read-only view for local or remote inspection. */
    public AtlasNetEntityStore(Shard shard) throws GErrorException {
        this(shard, id -> false);
    }

    AtlasNetEntityStore(Shard shard, LongPredicate canWrite) throws GErrorException {
        this.canWrite = canWrite;
        values = shard.getStateStore("minecraft.entities").getValues();
    }

    public CompletableFuture<Optional<Published>> load(long entityId) {
        var response = new CompletableFuture<Optional<Published>>();
        values.getBytesAsync(Ids.unsigned(entityId), result -> {
            try {
                var encoded = values.getBytesFinish(result);
                response.complete(encoded == null ? Optional.empty() :
                        Optional.of(new Published(decode(entityId, encoded), encoded)));
            } catch (GErrorException | IOException | RuntimeException error) {
                response.completeExceptionally(error);
            }
        });
        return response;
    }

    /**
     * Publish a revision from the active owner or install a newer owner generation
     * after AtlasNet authorizes a transfer. The caller supplies that authorization.
     */
    public CompletableFuture<Optional<Published>> publish(Published previous, Snapshot next) {
        var response = new CompletableFuture<Optional<Published>>();
        try {
            requireAuthority(next.entityId());
            verifyRevision(previous, next);
            var encoded = encode(next);
            var condition = previous == null ? StateStoreWriteCondition.IF_ABSENT : StateStoreWriteCondition.IF_MATCHES;
            var expected = previous == null ? null : binary(previous.encoded);
            values.putWithOptionsAsync(Ids.unsigned(next.entityId()), binary(encoded), 0, condition, expected, result -> {
                try {
                    response.complete(values.putWithOptionsFinish(result) ?
                            Optional.of(new Published(next, encoded)) : Optional.empty());
                } catch (GErrorException | RuntimeException error) {
                    response.completeExceptionally(error);
                }
            });
        } catch (IOException | RuntimeException error) {
            response.completeExceptionally(error);
        }
        return response;
    }

    public CompletableFuture<Boolean> retire(Published previous) {
        if (!canWrite.test(previous.snapshot.entityId()))
            return CompletableFuture.failedFuture(new IllegalStateException("No local publication authority"));
        var response = new CompletableFuture<Boolean>();
        values.deleteWithOptionsAsync(Ids.unsigned(previous.snapshot.entityId()),
                StateStoreWriteCondition.IF_MATCHES, binary(previous.encoded), result -> {
                    try { response.complete(values.deleteWithOptionsFinish(result)); }
                    catch (GErrorException | RuntimeException error) { response.completeExceptionally(error); }
                });
        return response;
    }

    private void requireAuthority(long id) {
        if (!canWrite.test(id)) throw new IllegalStateException("No local publication authority");
    }

    private static void verifyRevision(Published previous, Snapshot next) {
        if (next.entityId() == 0 || next.ownershipGeneration() <= 0 || next.revision() <= 0)
            throw new IllegalArgumentException("Invalid entity snapshot identity or revision");
        if (previous != null && (next.entityId() != previous.snapshot.entityId() ||
                !next.minecraftId().equals(previous.snapshot.minecraftId()) ||
                next.ownershipGeneration() < previous.snapshot.ownershipGeneration() ||
                next.revision() <= previous.snapshot.revision()))
            throw new IllegalArgumentException("Entity publication must advance the same entity's revision");
    }

    private static Variant binary(byte[] data) {
        return Variant.fromBytes(new VariantType("ay"), data, true);
    }

    static byte[] encode(Snapshot snapshot) throws IOException {
        var tag = new CompoundTag();
        tag.putLong("generation", snapshot.ownershipGeneration());
        tag.putLong("revision", snapshot.revision());
        tag.putUUID("minecraft_id", snapshot.minecraftId());
        tag.putString("type", snapshot.entityType());
        tag.putString("dimension", snapshot.dimension());
        tag.put("entity", snapshot.data());
        var output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, output);
        return output.toByteArray();
    }

    static Snapshot decode(long id, byte[] encoded) throws IOException {
        var tag = NbtIo.readCompressed(new ByteArrayInputStream(encoded), NbtAccounter.create(MAX_NBT_BYTES));
        return new Snapshot(id, tag.getLong("generation"), tag.getLong("revision"),
                tag.getUUID("minecraft_id"), tag.getString("type"), tag.getString("dimension"),
                tag.getCompound("entity"));
    }
}
