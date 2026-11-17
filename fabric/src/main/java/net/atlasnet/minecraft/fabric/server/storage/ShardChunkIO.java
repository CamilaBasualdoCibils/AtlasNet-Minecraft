package net.atlasnet.minecraft.fabric.server.storage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Vanilla terrain I/O for one authoritative Overworld region. */
public final class ShardChunkIO {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShardChunkIO.class);
    private static final boolean ENABLED = Boolean.getBoolean("atlasnet.minecraft.valkeyTerrain");
    private static ShardChunkIO instance;

    private final ValkeyChunkStorage storage;
    private final ChunkStorage.Authority authority;
    private final TerrainAuthority region = TerrainAuthority.fromWorker();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task ->
            Thread.ofPlatform().name("Minecraft Valkey chunk I/O").daemon().unstarted(task));
    private final LiveChunkHandoffs live;
    private volatile boolean revoked;
    private IOException failure;
    private long hits;
    private long misses;
    private long saves;
    private long readNanos;
    private long saveNanos;
    private final ConcurrentLinkedQueue<ChunkEvent> lifecycle = new ConcurrentLinkedQueue<>();

    private ShardChunkIO(ValkeyChunkStorage storage) throws IOException {
        this.storage = storage;
        authority = new ChunkStorage.Authority(storage.nextAuthorityEpoch(), UUID.randomUUID());
        String session = Boolean.getBoolean("atlasnet.minecraft.liveTerrain")
                ? System.getenv("MINECRAFT_TERRAIN_SESSION") : null;
        live = session == null ? null : new LiveChunkHandoffs(storage, UUID.fromString(session),
                authority.owner(), executor, region, (pos, loaded) -> lifecycle.add(new ChunkEvent(pos, loaded)));
    }

    public static void initialize() {
        if (ENABLED) {
            boolean normal = !"flat".equals(System.getenv("MINECRAFT_TERRAIN_PRESET"));
            var port = Integer.parseInt(System.getenv().getOrDefault("MINECRAFT_VALKEY_PORT",
                    normal ? "46382" : "46381"));
            try {
                instance = new ShardChunkIO(new ValkeyChunkStorage(new InetSocketAddress("127.0.0.1", port)));
                LOGGER.info("Using Minecraft Valkey terrain on port {}, authority epoch {}", port,
                        instance.authority.epoch());
            } catch (IOException error) {
                throw new IllegalStateException("Minecraft terrain storage is unavailable on port " + port, error);
            }
        }
    }

    public static ShardChunkIO current() {
        return instance;
    }

    public static boolean ownsTerrain(long chunk) {
        var io = instance;
        return io == null || (!io.revoked && io.authority(chunk) != null);
    }

    public static boolean ownsPosition(double x, double z) {
        var io = instance;
        return io != null && io.live != null && io.live.ownsPosition(x, z);
    }

    public static void revoke() {
        if (instance != null) {
            instance.revoked = true;
        }
    }

    private ChunkStorage.Authority authority(long chunk) {
        if (live != null) {
            return live.authority(chunk);
        }
        return region.owns(chunk) ? authority : null;
    }

    public void recordLifecycle(LevelChunk chunk, boolean loaded) {
        var pos = chunk.getPos();
        if (live != null) {
            if (loaded) {
                live.loaded(chunk);
            } else {
                live.unloaded(chunk);
            }
            return;
        }
        if (!region.owns(pos.toLong())) {
            return;
        }
        if (loaded) {
            lifecycle.add(new ChunkEvent(pos, true));
        } else {
            // ChunkMap queues the final save before ServerLevel.unload.
            submit(() -> {
                lifecycle.add(new ChunkEvent(pos, false));
                return null;
            });
        }
    }

    public static int[] takeLifecycle() {
        if (instance == null || instance.lifecycle.isEmpty()) {
            return null;
        }
        int[] events = new int[64 * 3];
        int index = 0;
        while (index < events.length) {
            var event = instance.lifecycle.poll();
            if (event == null) {
                break;
            }
            events[index++] = event.pos().x;
            events[index++] = event.pos().z;
            events[index++] = event.loaded() ? 1 : 0;
        }
        return java.util.Arrays.copyOf(events, index);
    }

    public CompletableFuture<Optional<CompoundTag>> read(ChunkPos pos) {
        var key = key(pos);
        return submit(() -> {
            long start = System.nanoTime();
            var chunk = live == null && region.owns(pos.toLong()) ? claimAndLoad(key) : storage.loadReplica(key);
            readNanos += System.nanoTime() - start;
            if (chunk.isPresent()) {
                hits++;
            } else {
                misses++;
            }
            return chunk.map(ChunkStorage.StoredChunk::data);
        });
    }

    public CompletableFuture<Void> write(ChunkPos pos, CompoundTag tag) {
        var owner = authority(pos.toLong());
        if (owner == null) {
            return CompletableFuture.completedFuture(null);
        }
        var key = key(pos);
        return submit(() -> {
            long start = System.nanoTime();
            // Always read the current version so unloading a chunk needs no version cache.
            if (live == null) {
                claim(key);
            }
            long version = storage.version(key, owner);
            requireAuthority();
            if (!storage.save(key, owner, version, tag)) {
                throw new IOException("Terrain write lost authority or version for " + key);
            }
            saves++;
            saveNanos += System.nanoTime() - start;
            return null;
        });
    }

    public void flush() {
        submit(() -> {
            LOGGER.info("Minecraft Valkey terrain reads {} found, {} missing, {} saves (average {} us read, {} us save)",
                    hits, misses, saves, readNanos / Math.max(1, hits + misses) / 1_000,
                    saveNanos / Math.max(1, saves) / 1_000);
            return null;
        }).join();
    }

    public void close() {
        try {
            flush();
        } finally {
            executor.shutdown();
        }
    }

    public void updateRegion(double[] bounds) {
        live.region(bounds == null ? null : TerrainAuthority.fromBounds(bounds));
    }

    public void available(ChunkPos pos) {
        live.available(pos);
    }

    public void release(ChunkPos pos) {
        live.release(pos);
    }

    public byte[] prepare(DedicatedServer server, ChunkPos pos, UUID transfer) {
        return live.prepare(server, pos, transfer);
    }

    public boolean stage(DedicatedServer server, UUID transfer, byte[] payload) {
        return live.stage(server, transfer, payload);
    }

    public boolean commitRestore(DedicatedServer server, UUID transfer, BiConsumer<ServerLevel, LevelChunk> publish) {
        return live.commitRestore(server, transfer, publish);
    }

    public void commitExtract(DedicatedServer server, ChunkPos pos, UUID transfer) {
        live.commitExtract(server, pos, transfer);
    }

    public void abortExtract(DedicatedServer server, ChunkPos pos, UUID transfer) {
        live.abortExtract(server, pos, transfer);
    }

    public void abortRestore(DedicatedServer server, UUID transfer) {
        live.abortRestore(server, transfer);
    }

    private Optional<ChunkStorage.StoredChunk> claimAndLoad(ChunkKey key) throws IOException {
        claim(key);
        return storage.load(key, authority);
    }

    private void claim(ChunkKey key) throws IOException {
        if (!storage.claim(key, authority)) {
            throw new IOException("Terrain authority is held by a newer worker for " + key);
        }
    }

    private void requireAuthority() throws IOException {
        if (revoked) {
            throw new IOException("The worker's Overworld terrain authority was revoked");
        }
    }

    private <T> CompletableFuture<T> submit(ChunkOperation<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (failure != null) {
                    throw failure;
                }
                requireAuthority();
                return operation.run();
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                    LOGGER.error("Minecraft Valkey terrain I/O failed", error);
                }
                // Vanilla recovers from a checked read error by generating new terrain.
                throw new CompletionException(new IllegalStateException("Minecraft terrain I/O is unavailable", error));
            }
        }, executor);
    }

    private static ChunkKey key(ChunkPos pos) {
        return new ChunkKey(Level.OVERWORLD.location(), pos.x, pos.z);
    }

    @FunctionalInterface
    private interface ChunkOperation<T> {
        T run() throws IOException;
    }

    private record ChunkEvent(ChunkPos pos, boolean loaded) {}
}
