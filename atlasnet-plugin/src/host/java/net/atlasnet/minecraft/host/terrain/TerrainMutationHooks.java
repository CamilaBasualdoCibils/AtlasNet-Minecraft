package net.atlasnet.minecraft.host.terrain;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

/** Entry points for the vanilla 1.21.1 terrain guards. */
public final class TerrainMutationHooks {
    public interface Policy {
        boolean mayTick(ChunkPos chunk);
        boolean maySetBlock(BlockPos position, BlockState state, int flags, int updateLimit);
        default void blockChanged(BlockPos position) {}
    }

    private static final Map<Level, Policy> policies = new WeakHashMap<>();
    private static final Map<LevelTicks<?>, Policy> tickPolicies = new WeakHashMap<>();

    private TerrainMutationHooks() {}

    /** AtlasNet owns player presentation on both sides of a shard boundary. */
    public static boolean mayTrackEntity(net.minecraft.world.entity.Entity entity) {
        return !(entity instanceof net.minecraft.server.level.ServerPlayer && entity.getId() < -1);
    }

    public static boolean mayBroadcastLocalChat(net.minecraft.network.chat.PlayerChatMessage message,
            net.minecraft.server.level.ServerPlayer sender, net.minecraft.network.chat.ChatType.Bound chatType) {
        return !net.atlasnet.minecraft.server.MinecraftShardBridge.broadcastPlayerChat(message, sender, chatType);
    }

    public static synchronized void install(ServerLevel level, Policy policy) {
        TerrainMutationAgent.requireHooks();
        policies.put(level, policy);
        tickPolicies.put(level.getBlockTicks(), policy);
        tickPolicies.put(level.getFluidTicks(), policy);
    }

    private static synchronized Policy policy(Level level) {
        return policies.get(level);
    }

    public static boolean maySetBlock(Level level, BlockPos position, BlockState state, int flags, int updateLimit) {
        var policy = policy(level);
        return policy == null || policy.maySetBlock(position, state, flags, updateLimit);
    }

    public static void blockChanged(Level level, BlockPos position) {
        var policy = policy(level);
        if (policy != null) policy.blockChanged(position);
    }

    public static boolean mayTickScheduled(ServerLevel level, long position) {
        var policy = policy(level);
        return policy == null || policy.mayTick(new ChunkPos(position));
    }

    public static boolean mayTickChunk(ServerLevel level, LevelChunk chunk) {
        var policy = policy(level);
        return policy == null || policy.mayTick(chunk.getPos());
    }

    public static boolean mayTickBlockEntity(BlockEntity blockEntity) {
        var policy = policy(blockEntity.getLevel());
        return policy == null || policy.mayTick(new ChunkPos(blockEntity.getBlockPos()));
    }

    public static void blockEntityChanged(Level level, BlockPos position) {
        markChanged(policy(level), position);
    }

    public static void scheduledTickAdded(LevelTicks<?> ticks, ScheduledTick<?> tick) {
        Policy policy;
        synchronized (TerrainMutationHooks.class) {
            policy = tickPolicies.get(ticks);
        }
        markChanged(policy, tick.pos());
    }

    public static void scheduledTickConsumed(ServerLevel level, BlockPos position) {
        markChanged(policy(level), position);
    }

    private static void markChanged(Policy policy, BlockPos position) {
        if (policy != null && policy.mayTick(new ChunkPos(position))) policy.blockChanged(position);
    }
}
