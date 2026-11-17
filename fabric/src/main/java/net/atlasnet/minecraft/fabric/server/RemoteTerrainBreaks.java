package net.atlasnet.minecraft.fabric.server;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;

/** Relays creative breaks that require no inventory or block-entity transaction. */
final class RemoteTerrainBreaks {
    private final Set<Long> outstanding = new HashSet<>();
    private final ConcurrentLinkedQueue<Request> pending = new ConcurrentLinkedQueue<>();

    boolean intercept(long entity, ServerPlayer player, ServerboundPlayerActionPacket action) {
        BlockPos pos = action.getPos();
        if (ShardChunkIO.ownsTerrain(ChunkPos.asLong(pos))) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        if (action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                && player.isCreative() && player.getMainHandItem().isEmpty()
                && !level.isOutsideBuildHeight(pos) && player.canInteractWithBlock(pos, 1.0)
                && level.mayInteract(player, pos) && outstanding.add(entity)) {
            pending.add(new Request(entity, pos, action.getSequence()));
        } else {
            player.connection.ackBlockChangesUpTo(action.getSequence());
        }
        return true;
    }

    long[] next() {
        Request request = pending.poll();
        return request == null ? null : new long[] {
                request.entity(), request.pos().getX(), request.pos().getY(),
                request.pos().getZ(), request.sequence()};
    }

    void complete(long entity, ServerPlayer player, BlockPos pos, int sequence, int state) {
        outstanding.remove(entity);
        if (player == null) {
            return;
        }
        if (state >= 0) {
            player.connection.send(new ClientboundBlockUpdatePacket(pos, Block.stateById(state)));
        }
        player.connection.ackBlockChangesUpTo(sequence);
    }

    static int breakBlock(ServerLevel level, BlockPos pos) {
        var chunkPos = new ChunkPos(pos);
        if (!ShardChunkIO.ownsTerrain(chunkPos.toLong()) || level.isOutsideBuildHeight(pos)
                || !level.getWorldBorder().isWithinBounds(pos)) {
            return -1;
        }
        var chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        if (chunk == null) {
            return -1;
        }
        var state = chunk.getBlockState(pos);
        if (!state.isAir() && state.getBlock().getClass() == Block.class
                && !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && state.getDestroySpeed(level, pos) >= 0) {
            level.destroyBlock(pos, false);
        }
        return Block.getId(chunk.getBlockState(pos));
    }

    private record Request(long entity, BlockPos pos, int sequence) {}
}
