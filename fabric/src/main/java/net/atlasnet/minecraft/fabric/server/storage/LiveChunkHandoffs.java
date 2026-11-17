package net.atlasnet.minecraft.fabric.server.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import net.atlasnet.minecraft.fabric.server.ShardPlayers;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Game-thread chunk residency, with Valkey fencing shared by all workers of one stack. */
final class LiveChunkHandoffs {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveChunkHandoffs.class);
    private static final TicketType<Long> TRANSFER = TicketType.create("atlasnet_chunk_transfer", Long::compare);
    private static final int CHECKPOINT_BYTES = 2 * Integer.BYTES + 4 * Long.BYTES;

    private final ValkeyChunkStorage storage;
    private final UUID session;
    private final UUID worker;
    private final Executor io;
    private final BiConsumer<ChunkPos, Boolean> lifecycle;
    private final Map<Long, LevelChunk> loaded = new HashMap<>();
    private final Set<Long> acquiring = new HashSet<>();
    private final Set<Long> retryAcquisition = new HashSet<>();
    private final Set<Long> staging = new HashSet<>();
    private final Set<Long> frozen = ConcurrentHashMap.newKeySet();
    private final Map<Long, ChunkStorage.Authority> active = new ConcurrentHashMap<>();
    private final Map<Long, ChunkStorage.Authority> vacated = new HashMap<>();
    private final Map<UUID, Checkpoint> exports = new HashMap<>();
    private final Map<UUID, Restore> imports = new HashMap<>();
    private volatile TerrainAuthority region;

    LiveChunkHandoffs(ValkeyChunkStorage storage, UUID session, UUID worker, Executor io,
                      TerrainAuthority region, BiConsumer<ChunkPos, Boolean> lifecycle) {
        this.storage = storage;
        this.session = session;
        this.worker = worker;
        this.io = io;
        this.region = region;
        this.lifecycle = lifecycle;
    }

    ChunkStorage.Authority authority(long chunk) {
        return region != null && region.owns(chunk) && !frozen.contains(chunk) ? active.get(chunk) : null;
    }

    boolean ownsPosition(double x, double z) {
        return region != null && region.contains(x, z);
    }

    void region(TerrainAuthority next) {
        if (java.util.Objects.equals(region, next)) {
            return;
        }
        region = next;
        for (var chunk : loaded.values()) {
            acquire(chunk);
        }
    }

    void loaded(LevelChunk chunk) {
        loaded.put(chunk.getPos().toLong(), chunk);
        acquire(chunk);
    }

    void unloaded(LevelChunk chunk) {
        long id = chunk.getPos().toLong();
        if (!loaded.remove(id, chunk)) {
            return;
        }
        var owner = active.remove(id);
        if (owner != null) {
            vacated.put(id, owner);
            // Prior Vanilla writes are already queued on the same executor.
            io.execute(() -> lifecycle.accept(chunk.getPos(), false));
        }
    }

    void release(ChunkPos pos) {
        long id = pos.toLong();
        var owner = vacated.get(id);
        if (owner == null) {
            return;
        }
        waitIO(() -> storage.releaseResident(key(pos), owner, session));
        vacated.remove(id);
        available(pos);
    }

    void available(ChunkPos pos) {
        if (acquiring.contains(pos.toLong())) {
            retryAcquisition.add(pos.toLong());
            return;
        }
        var chunk = loaded.get(pos.toLong());
        if (chunk != null) {
            acquire(chunk);
        }
    }

    private void acquire(LevelChunk chunk) {
        var pos = chunk.getPos();
        long id = pos.toLong();
        if (region == null || !region.owns(id) || active.containsKey(id) || vacated.containsKey(id) || staging.contains(id)
                || !acquiring.add(id)) {
            return;
        }
        var level = (ServerLevel) chunk.getLevel();
        hold(level, pos);
        async(() -> {
            var owner = new ChunkStorage.Authority(storage.nextAuthorityEpoch(), worker);
            if (!storage.claimResident(key(pos), owner, session)) {
                return null;
            }
            return new Acquisition(owner, storage.load(key(pos), owner).map(ChunkStorage.StoredChunk::data)
                    .orElse(null));
        }).thenCompose(result -> level.getServer().submit(() -> {
            if (result == null) {
                return CompletableFuture.completedFuture(null);
            }
            if (region == null || !region.owns(id) || loaded.get(id) != chunk) {
                return async(() -> {
                    storage.releaseResident(key(pos), result.authority(), session);
                    return null;
                });
            }
            var saved = result.data();
            CompletableFuture<?> installed = saved != null
                    && ChunkSerializer.getChunkTypeFromTag(saved) == ChunkType.LEVELCHUNK
                    ? ChunkStateInstaller.install(level, chunk, saved) : CompletableFuture.completedFuture(null);
            return installed.thenCompose(ignored -> level.getServer().submit(() -> {
                active.put(id, result.authority());
                lifecycle.accept(pos, true);
                if (authority(id) != null) {
                    ShardPlayers.publishChunk(level, chunk);
                }
            }));
        }).thenCompose(completion -> completion)).whenComplete((ignored, error) -> level.getServer().execute(() -> {
            acquiring.remove(id);
            unhold(level, pos);
            if (error != null) {
                LOGGER.error("Could not activate Minecraft terrain {}", pos, error);
            }
            if (retryAcquisition.remove(id) && error == null) {
                available(pos);
            }
        }));
    }

    byte[] prepare(DedicatedServer server, ChunkPos pos, UUID transfer) {
        var origin = server.submit(() -> {
            var chunk = loaded.get(pos.toLong());
            var owner = active.get(pos.toLong());
            if (chunk == null || owner == null || !frozen.add(pos.toLong())) {
                return null;
            }
            hold(server.overworld(), pos);
            return new Acquisition(owner, ChunkSerializer.write(server.overworld(), chunk));
        }).join();
        if (origin == null) {
            return null;
        }
        var checkpoint = waitIO(() -> {
            long version = storage.version(key(pos), origin.authority());
            var saved = new Checkpoint(pos, origin.authority(), version + 1);
            exports.put(transfer, saved);
            return storage.prepareHandoff(key(pos), transfer, origin.authority(), version, origin.data()) ? saved : null;
        });
        if (checkpoint == null) {
            abortExtract(server, pos, transfer);
            return null;
        }
        return checkpoint.encode();
    }

    boolean stage(DedicatedServer server, UUID transfer, byte[] payload) {
        var checkpoint = Checkpoint.decode(payload);
        var pos = checkpoint.pos();
        if (region == null || !region.owns(pos.toLong())) {
            return false;
        }
        boolean available = server.submit(() -> {
            if (active.containsKey(pos.toLong()) || acquiring.contains(pos.toLong()) || !staging.add(pos.toLong())) {
                return false;
            }
            hold(server.overworld(), pos);
            return true;
        }).join();
        if (!available) {
            return false;
        }
        try {
            // This JNI callback runs on the shard worker, outside the server thread.
            var cache = server.overworld().getChunkSource();
            var levelChunk = cache.getChunkFuture(pos.x, pos.z,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true).join().orElse(null);
            if (!(levelChunk instanceof LevelChunk chunk)) {
                return false;
            }
            return waitIO(() -> {
                var saved = storage.loadPreparedHandoff(key(pos), transfer, checkpoint.authority(), checkpoint.version());
                if (saved.isEmpty() || ChunkSerializer.getChunkTypeFromTag(saved.get().data()) != ChunkType.LEVELCHUNK) {
                    return false;
                }
                var destination = new ChunkStorage.Authority(storage.nextAuthorityEpoch(), worker);
                imports.put(transfer, new Restore(checkpoint, destination, chunk, saved.get().data()));
                return true;
            });
        } finally {
            if (!waitIO(() -> imports.containsKey(transfer))) {
                server.execute(() -> finishStage(server.overworld(), pos));
            }
        }
    }

    boolean commitRestore(DedicatedServer server, UUID transfer, BiConsumer<ServerLevel, LevelChunk> publish) {
        var restore = waitIO(() -> {
            var pending = imports.get(transfer);
            if (pending == null || !storage.commitHandoff(key(pending.checkpoint().pos()), transfer,
                    pending.checkpoint().authority(), pending.destination(), pending.checkpoint().version())) {
                return null;
            }
            return pending;
        });
        if (restore == null) {
            return false;
        }
        server.submit(() -> ChunkStateInstaller.install(server.overworld(), restore.chunk(), restore.data()))
                .thenCompose(completion -> completion).join();
        server.submit(() -> {
            var pos = restore.checkpoint().pos();
            active.put(pos.toLong(), restore.destination());
            publish.accept(server.overworld(), restore.chunk());
            finishStage(server.overworld(), pos);
        }).join();
        waitIO(() -> imports.remove(transfer));
        return true;
    }

    void commitExtract(DedicatedServer server, ChunkPos pos, UUID transfer) {
        server.submit(() -> {
            active.remove(pos.toLong());
            frozen.remove(pos.toLong());
            unhold(server.overworld(), pos);
        }).join();
        waitIO(() -> exports.remove(transfer));
    }

    void abortExtract(DedicatedServer server, ChunkPos pos, UUID transfer) {
        waitIO(() -> {
            var checkpoint = exports.get(transfer);
            if (checkpoint != null) {
                storage.abortHandoff(key(pos), transfer, checkpoint.authority(), checkpoint.version());
                exports.remove(transfer);
            }
            return null;
        });
        server.submit(() -> {
            if (frozen.remove(pos.toLong())) {
                unhold(server.overworld(), pos);
            }
        }).join();
    }

    void abortRestore(DedicatedServer server, UUID transfer) {
        var restore = waitIO(() -> imports.remove(transfer));
        if (restore != null) {
            server.submit(() -> finishStage(server.overworld(), restore.checkpoint().pos())).join();
        }
    }

    private static void hold(ServerLevel level, ChunkPos pos) {
        level.getChunkSource().addRegionTicket(TRANSFER, pos, 0, pos.toLong());
    }

    private static void unhold(ServerLevel level, ChunkPos pos) {
        level.getChunkSource().removeRegionTicket(TRANSFER, pos, 0, pos.toLong());
    }

    private void finishStage(ServerLevel level, ChunkPos pos) {
        staging.remove(pos.toLong());
        unhold(level, pos);
    }

    private static ChunkKey key(ChunkPos pos) {
        return new ChunkKey(Level.OVERWORLD.location(), pos.x, pos.z);
    }

    private <T> T waitIO(StorageOperation<T> operation) {
        return async(operation).join();
    }

    private <T> CompletableFuture<T> async(StorageOperation<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.run();
            } catch (IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }, io);
    }

    @FunctionalInterface
    private interface StorageOperation<T> {
        T run() throws IOException;
    }

    private record Acquisition(ChunkStorage.Authority authority, CompoundTag data) {}
    private record Restore(Checkpoint checkpoint, ChunkStorage.Authority destination, LevelChunk chunk, CompoundTag data) {}

    private record Checkpoint(ChunkPos pos, ChunkStorage.Authority authority, long version) {
        byte[] encode() {
            return ByteBuffer.allocate(CHECKPOINT_BYTES).putInt(pos.x).putInt(pos.z)
                    .putLong(authority.epoch()).putLong(authority.owner().getMostSignificantBits())
                    .putLong(authority.owner().getLeastSignificantBits()).putLong(version).array();
        }

        static Checkpoint decode(byte[] payload) {
            if (payload.length != CHECKPOINT_BYTES) {
                throw new IllegalArgumentException("Invalid chunk checkpoint size");
            }
            var bytes = ByteBuffer.wrap(payload);
            var pos = new ChunkPos(bytes.getInt(), bytes.getInt());
            var owner = new ChunkStorage.Authority(bytes.getLong(), new UUID(bytes.getLong(), bytes.getLong()));
            return new Checkpoint(pos, owner, bytes.getLong());
        }
    }
}
