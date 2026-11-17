package net.atlasnet.minecraft.fabric.server.storage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import net.atlasnet.minecraft.atlasnet.world.AtlasNetWorlds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

public final class ValkeyChunkStorageTest {
    private static final ChunkKey KEY = new ChunkKey(
            ResourceLocation.parse(AtlasNetWorlds.OVERWORLD_DIMENSION), -18, 37);
    private static final ChunkKey PREPARED_KEY = new ChunkKey(KEY.dimension(), -17, 37);
    private static final ChunkKey RESIDENT_KEY = new ChunkKey(KEY.dimension(), -16, 37);
    private static final ChunkStorage.Authority FIRST = new ChunkStorage.Authority(
            9_007_199_254_740_992L, new UUID(0, 1));
    private static final ChunkStorage.Authority SECOND = new ChunkStorage.Authority(
            FIRST.epoch() + 1, new UUID(0, 2));
    private static final ChunkStorage.Authority THIRD = new ChunkStorage.Authority(
            SECOND.epoch() + 1, new UUID(0, 3));
    private static final UUID HANDOFF = new UUID(1, 0);
    private static final UUID ABORTED_HANDOFF = new UUID(1, 1);

    public static void main(String[] args) throws Exception {
        checkTerrainAuthority();
        int port = Integer.parseInt(System.getenv().getOrDefault("MINECRAFT_VALKEY_PORT", "46381"));
        var storage = new ValkeyChunkStorage(new InetSocketAddress("127.0.0.1", port));
        if (args.length == 2 && args[0].equals("verify-region")) {
            verifyRegion(storage, Path.of(args[1]));
            return;
        }
        CompoundTag saved = data("minecraft:grass_block");
        if (args.length > 0 && args[0].equals("verify-restart")) {
            check(storage.load(KEY, THIRD).orElseThrow().equals(new ChunkStorage.StoredChunk(5, saved)),
                    "chunk and authority must survive restart");
            check(!storage.claim(KEY, SECOND), "restart must preserve the authority fence");
            check(storage.commitHandoff(KEY, HANDOFF, SECOND, THIRD, 4),
                    "the same transfer can be acknowledged after a Valkey restart");
            var prepared = data(PREPARED_KEY, "minecraft:stone");
            check(storage.loadPreparedHandoff(PREPARED_KEY, HANDOFF, SECOND, 1)
                            .orElseThrow().equals(new ChunkStorage.StoredChunk(1, prepared)),
                    "an in-flight checkpoint keeps its reservation across a Valkey restart");
            check(!storage.claim(PREPARED_KEY, THIRD), "a restart must retain the in-flight write fence");
            check(storage.abortHandoff(PREPARED_KEY, HANDOFF, SECOND, 1), "source can abort after restart");
            check(storage.save(PREPARED_KEY, SECOND, 1, prepared), "source can save again after abort");
            System.out.println("Prepared and committed chunk handoffs survived Valkey restart");
            return;
        }

        check(storage.claim(KEY, FIRST), "initial authority claim");
        check(storage.claim(KEY, FIRST), "same claim is idempotent");
        check(!storage.claim(KEY, new ChunkStorage.Authority(FIRST.epoch(), SECOND.owner())),
                "a different owner cannot claim the same epoch");
        check(storage.load(KEY, FIRST).isEmpty(), "new chunk has no payload");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> storage.save(KEY, FIRST, 0, saved));
            var b = executor.submit(() -> storage.save(KEY, FIRST, 0, saved));
            check(a.get() != b.get(), "only one concurrent initial save may commit");
        }
        check(storage.load(KEY, FIRST).orElseThrow().equals(new ChunkStorage.StoredChunk(1, saved)),
                "full NBT round trip");

        var misplaced = saved.copy();
        misplaced.putInt("xPos", KEY.x() + 1);
        try {
            storage.save(KEY, FIRST, 1, misplaced);
            throw new AssertionError("misplaced NBT must be rejected");
        } catch (IOException expected) {
            check(expected.getMessage().contains("coordinates do not match"), "expected coordinate error");
        }
        check(storage.load(KEY, FIRST).orElseThrow().version() == 1,
                "misplaced NBT must not advance the revision");

        check(storage.claim(KEY, SECOND), "new authority takes over");
        check(!storage.claim(KEY, FIRST), "stale authority cannot reclaim");
        check(!storage.save(KEY, FIRST, 1, data("minecraft:air")), "stale authority cannot overwrite");
        try {
            storage.load(KEY, FIRST);
            throw new AssertionError("stale authority cannot load");
        } catch (IOException expected) {
            check(expected.getMessage().contains("authority changed"), "expected authority-specific rejection");
        }
        check(!storage.save(KEY, SECOND, 0, saved), "handoff preserves the stored revision");
        check(storage.save(KEY, SECOND, 1, saved), "new authority can update current revision");

        check(storage.prepareHandoff(KEY, ABORTED_HANDOFF, SECOND, 2, saved), "save and reserve a checkpoint");
        check(storage.prepareHandoff(KEY, ABORTED_HANDOFF, SECOND, 2, saved), "matching preparation can be retried");
        check(storage.loadPreparedHandoff(KEY, ABORTED_HANDOFF, SECOND, 3)
                        .orElseThrow().equals(new ChunkStorage.StoredChunk(3, saved)),
                "a destination can stage the reserved checkpoint");
        check(storage.loadPreparedHandoff(KEY, HANDOFF, SECOND, 3).isEmpty(),
                "another transfer cannot stage this checkpoint");
        check(!storage.prepareHandoff(KEY, HANDOFF, SECOND, 3, saved), "only one transfer may reserve a chunk");
        check(!storage.abortHandoff(KEY, HANDOFF, SECOND, 3), "different transfer cannot release the reservation");
        check(!storage.claim(KEY, THIRD), "ordinary claim cannot bypass a prepared handoff");
        check(!storage.save(KEY, SECOND, 3, data("minecraft:air")), "prepared checkpoint rejects source writes");
        check(storage.abortHandoff(KEY, ABORTED_HANDOFF, SECOND, 3), "abort restores the source's ability to write");
        check(storage.abortHandoff(KEY, ABORTED_HANDOFF, SECOND, 3), "abort can be retried");
        check(storage.loadPreparedHandoff(KEY, ABORTED_HANDOFF, SECOND, 3).isEmpty(),
                "an aborted checkpoint is no longer reserved");
        check(!storage.commitHandoff(KEY, ABORTED_HANDOFF, SECOND, THIRD, 3), "aborted transfer cannot commit");
        check(storage.load(KEY, SECOND).orElseThrow().equals(new ChunkStorage.StoredChunk(3, saved)),
                "aborted transfer preserves the source checkpoint");

        check(storage.prepareHandoff(KEY, HANDOFF, SECOND, 3, saved), "source can prepare a later transfer");
        check(!storage.commitHandoff(KEY, HANDOFF, SECOND, THIRD, 3),
                "handoff must reject a stale checkpoint");
        check(!storage.commitHandoff(KEY, HANDOFF, FIRST, THIRD, 4),
                "handoff must reject a stale source");
        check(storage.commitHandoff(KEY, HANDOFF, SECOND, THIRD, 4),
                "handoff moves the write fence for the exact saved revision");
        check(storage.loadPreparedHandoff(KEY, HANDOFF, SECOND, 4).isEmpty(),
                "a committed transfer cannot stage again");
        check(!storage.abortHandoff(KEY, HANDOFF, SECOND, 4), "commit cannot be undone by an abort");
        check(!storage.save(KEY, SECOND, 4, data("minecraft:air")),
                "old owner cannot modify the committed checkpoint");
        check(storage.load(KEY, THIRD).orElseThrow().equals(new ChunkStorage.StoredChunk(4, saved)),
                "new owner reads the exact checkpoint");
        check(storage.save(KEY, THIRD, 4, saved), "new owner can save from the migrated revision");
        check(storage.commitHandoff(KEY, HANDOFF, SECOND, THIRD, 4),
                "retry succeeds even after a destination save");
        check(!storage.commitHandoff(KEY, ABORTED_HANDOFF, SECOND, THIRD, 4),
                "different transfer cannot claim a committed destination");
        check(!storage.commitHandoff(KEY, HANDOFF, SECOND, THIRD, 5),
                "same transfer cannot acknowledge a different checkpoint");

        check(storage.claim(PREPARED_KEY, SECOND), "claim the in-flight restart fixture");
        check(storage.prepareHandoff(PREPARED_KEY, HANDOFF, SECOND, 0, data(PREPARED_KEY, "minecraft:stone")),
                "prepare a new checkpoint for the restart test");

        var stack = new UUID(2, 0);
        check(storage.claimResident(RESIDENT_KEY, FIRST, stack), "first worker gains residency");
        check(!storage.claimResident(RESIDENT_KEY, SECOND, stack), "same session cannot put two workers on one chunk");
        check(!storage.claim(RESIDENT_KEY, SECOND), "legacy claims cannot bypass active residency");
        check(!storage.releaseResident(RESIDENT_KEY, FIRST, new UUID(2, 1)), "wrong session cannot release residency");
        check(storage.releaseResident(RESIDENT_KEY, FIRST, stack), "previous worker can release residency");
        check(storage.claimResident(RESIDENT_KEY, SECOND, stack), "available chunk can be loaded by a new worker");
        check(!storage.releaseResident(RESIDENT_KEY, FIRST, stack), "a delayed release cannot unbind the new owner");

        try {
            new ChunkKey(ResourceLocation.parse("minecraft:the_nether"), -18, 37);
            throw new AssertionError("Nether key must be rejected");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("Unsupported chunk dimension"), "dimension error");
        }
        System.out.println("Verified NBT round trip, concurrent creation, and fenced handoff retries");
    }

    private static void checkTerrainAuthority() {
        double min = Double.NEGATIVE_INFINITY;
        double max = Double.POSITIVE_INFINITY;
        var first = TerrainAuthority.fromBounds(new double[] {min, min, max, max, 1, -1, 0, 1});
        var second = TerrainAuthority.fromBounds(new double[] {min, min, max, max, -1, 1, 0, 0});
        long north = ChunkPos.asLong(0, -1);
        long south = ChunkPos.asLong(0, 1);
        long tie = ChunkPos.asLong(0, 0);
        check(!first.owns(north) && second.owns(north), "the XZ test plane must classify negative Z");
        check(first.owns(south) && !second.owns(south), "the XZ test plane must classify positive Z");
        check(!first.owns(tie) && second.owns(tie), "exact boundary belongs to one shard");
    }

    private static void verifyRegion(ChunkStorage storage, Path source) throws IOException {
        String[] parts = source.getFileName().toString().split("\\.");
        if (parts.length != 4 || !parts[0].equals("r") || !parts[3].equals("mca")) {
            throw new IllegalArgumentException("Expected a Minecraft region file named r.x.z.mca");
        }
        int startX = Math.multiplyExact(Integer.parseInt(parts[1]), 32);
        int startZ = Math.multiplyExact(Integer.parseInt(parts[2]), 32);
        Path directory = Files.createTempDirectory(Path.of("build"), "chunk-region-fixture.");
        Path copied = directory.resolve(source.getFileName());
        try {
            Files.copy(source, copied);
            var info = new RegionStorageInfo("chunk-storage-test", Level.OVERWORLD, "chunk");
            try (var region = new RegionFile(info, copied, directory, false)) {
                for (int offset = 0; offset < 1024; offset++) {
                    var pos = new ChunkPos(startX + offset % 32, startZ + offset / 32);
                    try (var input = region.getChunkDataInputStream(pos)) {
                        if (input == null) {
                            continue;
                        }
                        var data = NbtIo.read(input, NbtAccounter.create(64L * 1024 * 1024));
                        var key = new ChunkKey(Level.OVERWORLD.location(), pos.x, pos.z);
                        check(data.getInt("xPos") == pos.x && data.getInt("zPos") == pos.z,
                                "vanilla chunk coordinates match the storage key");
                        check(storage.claim(key, FIRST), "claim a generated chunk");
                        check(storage.save(key, FIRST, 0, data), "save generated chunk");
                        check(storage.load(key, FIRST).orElseThrow()
                                .equals(new ChunkStorage.StoredChunk(1, data)), "generated chunk NBT round trip");
                        System.out.printf("Generated Overworld chunk %s survived the Valkey NBT round trip%n", pos);
                        return;
                    }
                }
            }
            throw new AssertionError("Region file did not contain a readable chunk");
        } finally {
            Files.deleteIfExists(copied);
            Files.delete(directory);
        }
    }

    private static CompoundTag data(String block) {
        return data(KEY, block);
    }

    private static CompoundTag data(ChunkKey key, String block) {
        var chunk = new CompoundTag();
        chunk.putInt("DataVersion", 3955);
        chunk.putInt("xPos", key.x());
        chunk.putInt("zPos", key.z());
        var section = new CompoundTag();
        section.putString("Name", block);
        section.putLongArray("values", new long[] {1L, -16L, Long.MAX_VALUE});
        chunk.put("test_section", section);
        return chunk;
    }

    private static void check(boolean valid, String message) {
        if (!valid) {
            throw new AssertionError(message);
        }
    }
}
