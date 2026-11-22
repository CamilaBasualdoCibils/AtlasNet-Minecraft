package net.atlasnet.minecraft.fabric.server;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Applies authoritative block deltas to the server-side terrain used for collision. */
final class TerrainCollisionReplicas {
    private static final int HEADER = 2 * Integer.BYTES + 2 * Long.BYTES;
    private static final int RECORD = Long.BYTES + Integer.BYTES;
    private static final int MAX_SECTION_BLOCKS = 16 * 16 * 16;
    private static final int MAX_PENDING_CHUNKS = 256;
    private final ConcurrentLinkedQueue<byte[]> outbound = new ConcurrentLinkedQueue<>();
    private final HashMap<Long, Replica> loaded = new HashMap<>();
    private final LinkedHashMap<Long, Replica> pending = new LinkedHashMap<>();
    private long sequence;

    void capture(ChunkPos chunk, Packet<?> packet) {
        long epoch = ShardChunkIO.fencedEpoch(chunk.toLong());
        if (epoch == 0) {
            return;
        }
        if (packet instanceof ClientboundBlockUpdatePacket block) {
            var frame = header(chunk, epoch, 1);
            encode(frame, block.getPos(), block.getBlockState());
            outbound.add(frame.array());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            var frame = header(chunk, epoch, MAX_SECTION_BLOCKS);
            section.runUpdates((pos, state) -> encode(frame, pos, state));
            outbound.add(java.util.Arrays.copyOf(frame.array(), frame.position()));
        }
    }

    byte[] next() {
        return outbound.poll();
    }

    void receive(ServerLevel level, byte[] data) {
        if (data.length <= HEADER || data.length > HEADER + RECORD * MAX_SECTION_BLOCKS
                || (data.length - HEADER) % RECORD != 0) {
            return;
        }
        var frame = ByteBuffer.wrap(data);
        var pos = new ChunkPos(frame.getInt(), frame.getInt());
        long key = pos.toLong();
        long epoch = frame.getLong();
        long revision = frame.getLong();
        if (!ShardChunkIO.canApplyCollisionReplica(key)) {
            return;
        }
        var chunk = ShardChunkIO.collisionReplica(key);
        var replicas = chunk == null ? pending : loaded;
        var replica = replicas.get(key);
        if (replica == null || replica.epoch < epoch) {
            replica = new Replica(epoch);
            replicas.put(key, replica);
            if (chunk == null && pending.size() > MAX_PENDING_CHUNKS) {
                pending.pollFirstEntry();
            }
        } else if (replica.epoch > epoch) {
            return;
        }
        while (frame.hasRemaining()) {
            long block = frame.getLong();
            int state = frame.getInt();
            var position = BlockPos.of(block);
            if (ChunkPos.asLong(position) != key || level.isOutsideBuildHeight(position)) {
                continue;
            }
            var previous = replica.changed.get(block);
            if (previous == null || previous.revision() < revision) {
                replica.changed.put(block, new Change(revision, state));
                if (chunk != null) {
                    setCollisionBlock(level, chunk, position, Block.stateById(state));
                }
            }
        }
    }

    void chunkLoaded(LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        var replica = pending.remove(key);
        if (replica == null || !ShardChunkIO.canApplyCollisionReplica(key)) {
            return;
        }
        loaded.put(key, replica);
        var level = (ServerLevel) chunk.getLevel();
        replica.changed.forEach((block, change) ->
                setCollisionBlock(level, chunk, BlockPos.of(block), Block.stateById(change.state())));
    }

    void chunkUnloaded(ChunkPos chunk) {
        loaded.remove(chunk.toLong());
    }

    private ByteBuffer header(ChunkPos chunk, long epoch, int records) {
        return ByteBuffer.allocate(HEADER + RECORD * records)
                .putInt(chunk.x).putInt(chunk.z).putLong(epoch).putLong(++sequence);
    }

    private static void encode(ByteBuffer frame, BlockPos pos, BlockState state) {
        frame.putLong(pos.asLong()).putInt(Block.getId(state));
    }

    private static void setCollisionBlock(ServerLevel level, LevelChunk chunk, BlockPos pos, BlockState state) {
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(pos.getY()));
        boolean wasEmpty = section.hasOnlyAir();
        int x = pos.getX() & 15;
        int z = pos.getZ() & 15;
        if (section.setBlockState(x, pos.getY() & 15, z, state) == state) {
            return;
        }
        for (var heightmap : chunk.getHeightmaps()) {
            heightmap.getValue().update(x, pos.getY(), z, state);
        }
        var light = level.getChunkSource().getLightEngine();
        if (wasEmpty != section.hasOnlyAir()) {
            light.updateSectionStatus(SectionPos.of(pos), section.hasOnlyAir());
        }
        light.checkBlock(pos);
    }

    private static final class Replica {
        final long epoch;
        final HashMap<Long, Change> changed = new HashMap<>();

        Replica(long epoch) {
            this.epoch = epoch;
        }
    }

    private record Change(long revision, int state) {}
}
