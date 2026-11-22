package net.atlasnet.minecraft.fabric.server;

import java.util.HashMap;
import java.util.Map;
import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;

/** Tracks vanilla mining time with the player while the chunk owner handles block mutations. */
final class RemoteTerrainBreaks {
    private final Map<Long, Dig> digging = new HashMap<>();

    boolean intercept(long entity, ServerPlayer player, ServerboundPlayerActionPacket action,
                      RemoteTerrainUses operations) {
        var pos = action.getPos();
        var active = digging.get(entity);
        if (ShardChunkIO.ownsTerrain(ChunkPos.asLong(pos))
                && (active == null || !active.pos().equals(pos))) {
            if (action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
                digging.remove(entity);
            }
            return false;
        }
        var sequence = action.getSequence();
        if (eligible(player, pos)) {
            switch (action.getAction()) {
                case START_DESTROY_BLOCK -> {
                    digging.remove(entity);
                    if (player.isCreative()) {
                        operations.destroy(entity, player, pos, sequence, -1);
                    } else {
                        operations.probeBreak(entity, player, pos, sequence, player.server.getTickCount());
                    }
                    return true;
                }
                case STOP_DESTROY_BLOCK -> {
                    if (active != null && active.pos().equals(pos)
                            && ItemStack.matches(active.tool(), player.getMainHandItem())) {
                        if (progress(player, active) >= 0.7F) {
                            digging.remove(entity);
                            operations.destroy(entity, player, pos, sequence, active.state());
                            return true;
                        }
                        digging.put(entity, new Dig(pos, active.state(), active.started(), active.tool(), sequence));
                        return true;
                    }
                }
                case ABORT_DESTROY_BLOCK -> digging.remove(entity);
                default -> {}
            }
        }
        player.connection.ackBlockChangesUpTo(sequence);
        return true;
    }

    boolean confirm(long entity, ServerPlayer player, BlockPos pos, int sequence,
                    int started, int state, ItemStack tool, RemoteTerrainUses operations) {
        if (state < 0 || !eligible(player, pos) || Block.stateById(state).isAir()
                || !ItemStack.matches(tool, player.getMainHandItem())) {
            return false;
        }
        var dig = new Dig(pos, state, started, tool, -1);
        float speed = Block.stateById(state).getDestroyProgress(player, player.level(), pos);
        if (speed >= 1.0F) {
            operations.destroy(entity, player, pos, sequence, state);
            return true;
        }
        if (speed > 0.0F) {
            digging.put(entity, dig);
        }
        return false;
    }

    void tick(Map<Long, ServerPlayer> players, RemoteTerrainUses operations) {
        var iterator = digging.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var dig = entry.getValue();
            var player = players.get(entry.getKey());
            if (player == null || ShardPlayers.isFrozen(player)) {
                continue;
            }
            if (!eligible(player, dig.pos()) || !ItemStack.matches(dig.tool(), player.getMainHandItem())) {
                if (dig.finishSequence() >= 0) {
                    player.connection.ackBlockChangesUpTo(dig.finishSequence());
                }
                iterator.remove();
            } else if (dig.finishSequence() >= 0 && !operations.pending(entry.getKey())
                    && progress(player, dig) >= 1.0F) {
                iterator.remove();
                operations.destroy(entry.getKey(), player, dig.pos(), dig.finishSequence(), dig.state());
            }
        }
    }

    void save(long entity, ServerPlayer player, CompoundTag playerTag) {
        var active = digging.get(entity);
        if (active != null) {
            var tag = new CompoundTag();
            tag.putLong("Pos", active.pos().asLong());
            tag.putInt("State", active.state());
            tag.putInt("Ticks", player.server.getTickCount() - active.started());
            tag.putInt("FinishSequence", active.finishSequence());
            tag.put("Tool", active.tool().saveOptional(player.registryAccess()));
            playerTag.put("AtlasNetDig", tag);
        }
    }

    void restore(long entity, ServerPlayer player, CompoundTag playerTag) {
        if (playerTag.contains("AtlasNetDig")) {
            var tag = playerTag.getCompound("AtlasNetDig");
            digging.put(entity, new Dig(BlockPos.of(tag.getLong("Pos")), tag.getInt("State"),
                    player.server.getTickCount() - tag.getInt("Ticks"),
                    ItemStack.parseOptional(player.registryAccess(), tag.getCompound("Tool")),
                    tag.getInt("FinishSequence")));
        }
    }

    void discard(long entity) {
        digging.remove(entity);
    }

    private static float progress(ServerPlayer player, Dig dig) {
        int elapsed = player.server.getTickCount() - dig.started();
        return Block.stateById(dig.state()).getDestroyProgress(player, player.level(), dig.pos()) * (elapsed + 1);
    }

    private static boolean eligible(ServerPlayer player, BlockPos pos) {
        var level = player.serverLevel();
        return player.isAlive() && !player.isSpectator() && !level.isOutsideBuildHeight(pos)
                && level.getWorldBorder().isWithinBounds(pos) && player.canInteractWithBlock(pos, 1.0)
                && level.mayInteract(player, pos)
                && !player.blockActionRestricted(level, pos, player.gameMode.getGameModeForPlayer());
    }

    private record Dig(BlockPos pos, int state, int started, ItemStack tool, int finishSequence) {}
}
