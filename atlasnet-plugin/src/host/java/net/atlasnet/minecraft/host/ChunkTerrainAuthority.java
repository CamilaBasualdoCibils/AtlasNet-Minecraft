package net.atlasnet.minecraft.host;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import net.atlasnet.minecraft.server.terrain.AuthoritativeTerrainBackend;
import net.atlasnet.minecraft.server.terrain.TerrainStore;
import net.minecraft.resources.ResourceLocation;
import org.atlasnet.atlasnet.Shard;
import org.javagi.base.GErrorException;

/** Manages durable chunk write fences from the AtlasNet shard worker's entity callbacks. */
public final class ChunkTerrainAuthority {
    private static final String TRANSFER_KIND = "terrain/1";
    private static final System.Logger LOG = System.getLogger(ChunkTerrainAuthority.class.getName());

    private record Chunk(TerrainStore.ChunkKey key, TerrainStore.Fence fence,
                         CompletableFuture<Void> activation) {}
    private record Incoming(TerrainStore.ChunkKey key, TerrainStore.Fence predecessor) {}
    private record Placement(TerrainStore.ChunkKey key, long entity) {}

    private final ChunkEntityDirectory directory;
    private final TerrainStore store;
    private final Executor io;
    private final AuthoritativeTerrainBackend backend;
    private final BiFunction<TerrainStore.ChunkKey, TerrainStore.Fence, CompletableFuture<Void>> freeze;
    private final HashMap<Long, Chunk> owned = new HashMap<>();
    private final HashMap<Long, Incoming> incoming = new HashMap<>();
    private final ConcurrentLinkedQueue<Placement> placements = new ConcurrentLinkedQueue<>();

    public ChunkTerrainAuthority(Shard shard, TerrainStore store, Executor io,
            java.util.function.BiConsumer<TerrainStore.ChunkKey, Long> onCommit,
            BiFunction<TerrainStore.ChunkKey, TerrainStore.Snapshot, CompletableFuture<Void>> beforeActivation,
            BiFunction<TerrainStore.ChunkKey, TerrainStore.Fence, CompletableFuture<Void>> freeze)
            throws GErrorException {
        this.directory = new ChunkEntityDirectory(shard);
        this.store = store;
        this.io = io;
        this.backend = new AuthoritativeTerrainBackend(store, io, onCommit, beforeActivation);
        this.freeze = freeze;
        shard.onPoll(this::routeInitialPlacements);
    }

    public AuthoritativeTerrainBackend backend() {
        return backend;
    }

    public CompletableFuture<OptionalLong> lookup(TerrainStore.ChunkKey key) {
        return directory.lookup(key);
    }

    /** Register a chunk at its fixed spatial position. Call from the shard worker. */
    public CompletableFuture<OptionalLong> registerLoaded(TerrainStore.ChunkKey key) {
        return directory.register(key).thenCompose(registration -> {
            if (registration.isEmpty()) return CompletableFuture.completedFuture(registration);
            long id = registration.getAsLong();
            var fence = new TerrainStore.Fence(id, 1);
            var activation = CompletableFuture.supplyAsync(() -> {
                try {
                    return store.loadFence(key);
                } catch (IOException failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }, io).thenCompose(predecessor -> backend.activate(key, predecessor, fence));
            owned.put(id, new Chunk(key, fence, activation));
            return activation.thenApply(ignored -> registration);
        });
    }

    /** Queue placement after the game thread submits the first snapshot. */
    public void routeInitialPlacement(TerrainStore.ChunkKey key, long entity) {
        placements.add(new Placement(key, entity));
    }

    private void routeInitialPlacements() {
        for (Placement placement; (placement = placements.poll()) != null;) {
            try {
                directory.route(placement.key(), placement.entity());
            } catch (GErrorException failure) {
                LOG.log(System.Logger.Level.WARNING, "Could not route terrain " + placement.key(), failure);
            }
        }
    }

    public boolean owns(long id) {
        return owned.containsKey(id);
    }

    public byte[] prepareExtract(long id) {
        var chunk = owned.get(id);
        if (chunk == null || !successful(chunk.activation())) return null;
        var writes = freeze.apply(chunk.key(), chunk.fence());
        if (!successful(writes)) {
            backend.resume(chunk.key(), chunk.fence());
            return null;
        }
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            output.writeUTF(TRANSFER_KIND);
            output.writeUTF(chunk.key().world());
            output.writeUTF(chunk.key().dimension().toString());
            output.writeInt(chunk.key().x());
            output.writeInt(chunk.key().z());
            output.writeLong(chunk.fence().generation());
            return bytes.toByteArray();
        } catch (IOException failure) {
            backend.resume(chunk.key(), chunk.fence());
            return null;
        }
    }

    /** Empty identifies an ordinary entity payload. A present false rejects a malformed terrain handoff. */
    public Optional<Boolean> prepareRestore(long id, byte[] payload) {
        if (payload == null) return Optional.empty();
        try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (!TRANSFER_KIND.equals(input.readUTF())) return Optional.empty();
            var world = input.readUTF();
            var dimension = ResourceLocation.parse(input.readUTF());
            var key = new TerrainStore.ChunkKey(world, dimension, input.readInt(), input.readInt());
            var predecessor = new TerrainStore.Fence(id, input.readLong());
            if (input.available() != 0 || owned.containsKey(id)) return Optional.of(false);
            incoming.put(id, new Incoming(key, predecessor));
            return Optional.of(true);
        } catch (IOException | IllegalArgumentException failure) {
            return Optional.of(false);
        }
    }

    public boolean commitExtract(long id) {
        var chunk = owned.get(id);
        if (chunk == null || !backend.release(chunk.key(), chunk.fence())) return false;
        owned.remove(id);
        return true;
    }

    public boolean hasIncoming(long id) {
        return incoming.containsKey(id);
    }

    public boolean commitRestore(long id) {
        var staged = incoming.get(id);
        if (staged == null) return false;
        var chunk = owned.get(id);
        if (chunk == null || chunk.activation().isCompletedExceptionally()) {
            var fence = new TerrainStore.Fence(id, Math.addExact(staged.predecessor().generation(), 1));
            var activation = backend.activate(staged.key(), Optional.of(staged.predecessor()), fence);
            chunk = new Chunk(staged.key(), fence, activation);
            owned.put(id, chunk);
        }
        if (!successful(chunk.activation())) return false;
        incoming.remove(id);
        return true;
    }

    public void abortExtract(long id) {
        var chunk = owned.get(id);
        if (chunk != null) backend.resume(chunk.key(), chunk.fence());
    }

    public void abortRestore(long id) {
        incoming.remove(id);
    }

    private static boolean successful(CompletableFuture<Void> result) {
        return result.isDone() && !result.isCompletedExceptionally();
    }
}
