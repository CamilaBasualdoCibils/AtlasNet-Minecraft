package net.atlasnet.minecraft.server.terrain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;

/** Minecraft 1.21.1 terrain storage boundary. Vanilla still serializes and upgrades chunk NBT. */
public final class MinecraftTerrainIO extends IOWorker {
    public interface Backend {
        CompletableFuture<Optional<CompoundTag>> load(TerrainStore.ChunkKey key);

        default boolean maySave(TerrainStore.ChunkKey key) { return true; }

        /** Accepts snapshots in submission order and completes after persistence acknowledges them. */
        CompletableFuture<Void> save(TerrainStore.ChunkKey key, CompoundTag snapshot);
    }

    private final Backend backend;
    private final String world;
    private final net.minecraft.resources.ResourceLocation dimension;
    private record Pending(CompoundTag snapshot, CompletableFuture<Void> completion) {}

    private final HashMap<ChunkPos, Pending> writes = new HashMap<>();
    private boolean closed;

    private MinecraftTerrainIO(IOWorker original, Path storagePath, String world, Backend backend) {
        super(original.storageInfo(), storagePath, false);
        this.world = world;
        this.dimension = original.storageInfo().dimension().location();
        this.backend = backend;
    }

    /** Install before the level begins loading its terrain. */
    public static void install(ServerLevel level, Path storagePath, String world, Backend backend) throws IOException {
        try {
            var field = ChunkStorage.class.getDeclaredField("worker");
            field.setAccessible(true);
            var chunkMap = level.getChunkSource().chunkMap;
            var original = (IOWorker) field.get(chunkMap);
            var replacement = new MinecraftTerrainIO(original, storagePath, world, backend);
            original.close();
            field.set(chunkMap, replacement);
        } catch (ReflectiveOperationException failure) {
            throw new IOException("Cannot install Minecraft 1.21.1 terrain I/O", failure);
        }
    }

    @Override public synchronized CompletableFuture<Optional<CompoundTag>> loadAsync(ChunkPos position) {
        var key = key(position);
        var pending = writes.get(position);
        if (pending == null) return backend.load(key);
        return pending.completion().handle((ignored, failure) -> {
            if (failure != null && backend.maySave(key)) {
                return CompletableFuture.completedFuture(Optional.of(pending.snapshot().copy()));
            }
            return backend.load(key);
        }).thenCompose(result -> result);
    }

    @Override public CompletableFuture<Void> scanChunk(ChunkPos position, StreamTagVisitor visitor) {
        return loadAsync(position).thenAccept(data -> data.ifPresent(tag -> tag.acceptAsRoot(visitor)));
    }

    @Override public synchronized CompletableFuture<Void> store(ChunkPos position, CompoundTag data) {
        if (closed) return CompletableFuture.failedFuture(new IOException("Terrain storage is closed"));
        if (data == null) return CompletableFuture.failedFuture(
                new IllegalArgumentException("A terrain save requires a chunk snapshot"));
        if (!backend.maySave(key(position))) return CompletableFuture.completedFuture(null);
        return queue(position, data.copy(), 1);
    }

    private CompletableFuture<Void> queue(ChunkPos position, CompoundTag snapshot, int retryDelay) {
        // Submit now so an authority freeze sees every accepted snapshot, including queued writes.
        var write = backend.save(key(position), snapshot);
        var pending = new Pending(snapshot, write);
        writes.put(position, pending);
        write.whenComplete((ignored, failure) -> {
            if (failure == null) {
                synchronized (this) { writes.remove(position, pending); }
            } else {
                CompletableFuture.delayedExecutor(retryDelay, TimeUnit.SECONDS).execute(() -> {
                    synchronized (this) {
                        if (!closed && writes.get(position) == pending && backend.maySave(key(position))) {
                            queue(position, snapshot, Math.min(30, retryDelay * 2));
                        }
                    }
                });
            }
        });
        return write;
    }

    @Override public synchronized CompletableFuture<Void> synchronize(boolean flush) {
        var outstanding = new java.util.ArrayList<CompletableFuture<Void>>(writes.size());
        new HashMap<>(writes).forEach((position, pending) -> outstanding.add(
                pending.completion().isCompletedExceptionally()
                        ? queue(position, pending.snapshot(), 1) : pending.completion()));
        return CompletableFuture.allOf(outstanding.toArray(CompletableFuture[]::new));
    }

    @Override public void close() throws IOException {
        synchronized (this) { closed = true; }
        super.close();
    }

    private TerrainStore.ChunkKey key(ChunkPos position) {
        return new TerrainStore.ChunkKey(world, dimension, position.x, position.z);
    }
}
