package net.atlasnet.minecraft.fabric.server;

import com.mojang.authlib.GameProfile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Forwards world-mutating uses through the terrain owner's existing SpatialRPC path. */
final class RemoteTerrainUses {
    private static final int MAX_STATE_BYTES = 16 * 1024;
    private final Map<Long, Use> outstanding = new HashMap<>();
    private final ConcurrentLinkedQueue<byte[]> pending = new ConcurrentLinkedQueue<>();
    private final Set<ServerPlayer> reservedInventories = new HashSet<>();
    private final Set<ServerPlayer> deferredEquipmentDrops = new HashSet<>();

    boolean pending(long entity) {
        return outstanding.containsKey(entity);
    }

    boolean inventoryReserved(ServerPlayer player) {
        return reservedInventories.contains(player);
    }

    boolean deferEquipmentDrop(ServerPlayer player) {
        if (!inventoryReserved(player)) {
            return false;
        }
        deferredEquipmentDrops.add(player);
        return true;
    }

    boolean chestClick(long entity, ServerPlayer player, ServerboundContainerClickPacket click) {
        if (!(player.containerMenu instanceof RemoteTerrainChests.RemoteMenu menu)) {
            return false;
        }
        var input = RemoteTerrainChests.input(menu, player, click);
        if (input == null) {
            menu.broadcastFullState();
            return true;
        }
        var state = playerState(player);
        state.put("ChestClick", input);
        var request = encode(state);
        if (request == null) {
            menu.broadcastFullState();
            return true;
        }
        var target = menu.target();
        reservedInventories.add(player);
        menu.beginClick();
        outstanding.put(entity, new Use(player, -1, ItemStack.EMPTY, -1, target, target, state, menu));
        enqueue(entity, target, request);
        player.resetLastActionTime();
        return true;
    }

    boolean intercept(long entity, ServerPlayer player, ServerboundUseItemOnPacket packet) {
        var hit = packet.getHitResult();
        var clicked = hit.getBlockPos();
        var adjacent = clicked.relative(hit.getDirection());
        var level = player.serverLevel();
        var hand = packet.getHand();
        var held = player.getItemInHand(hand);
        boolean bypassBlock = player.isSecondaryUseActive()
                && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty());
        if (owns(clicked) && (owns(adjacent)
                || (!bypassBlock && loaded(level, clicked)
                    && level.getBlockState(clicked).getMenuProvider(level, clicked) != null))) {
            return false;
        }
        var offset = hit.getLocation().subtract(Vec3.atCenterOf(clicked));
        if (!player.isAlive() || player.isSpectator() || player.isUsingItem()
                || !player.canInteractWithBlock(clicked, 1.0) || !level.mayInteract(player, clicked)
                || level.isOutsideBuildHeight(clicked) || !level.getWorldBorder().isWithinBounds(clicked)
                || !held.isItemEnabled(level.enabledFeatures())
                || !(Math.abs(offset.x) < 1.0000001 && Math.abs(offset.y) < 1.0000001
                     && Math.abs(offset.z) < 1.0000001)) {
            player.connection.ackBlockChangesUpTo(packet.getSequence());
            return true;
        }

        var state = playerState(player);
        state.putBoolean("Secondary", player.isSecondaryUseActive());
        state.putBoolean("BypassBlock", bypassBlock);
        state.putBoolean("CoolingDown", player.getCooldowns().isOnCooldown(held.getItem()));
        state.putBoolean("Offhand", hand == InteractionHand.OFF_HAND);
        state.putLong("Clicked", clicked.asLong());
        state.putInt("Face", hit.getDirection().get3DDataValue());
        state.putDouble("HitX", hit.getLocation().x);
        state.putDouble("HitY", hit.getLocation().y);
        state.putDouble("HitZ", hit.getLocation().z);
        state.putBoolean("Inside", hit.isInside());
        int slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : Inventory.SLOT_OFFHAND;
        submit(entity, player, slot, held, packet.getSequence(), clicked, adjacent, state);
        return true;
    }

    void destroy(long entity, ServerPlayer player, BlockPos target, int sequence, int expectedState) {
        var state = playerState(player);
        state.putInt("DestroyState", expectedState);
        state.putBoolean("GameMaster", player.canUseGameMasterBlocks());
        submit(entity, player, player.getInventory().selected,
                player.getMainHandItem(), sequence, target, target, state);
    }

    void probeBreak(long entity, ServerPlayer player, BlockPos target, int sequence, int started) {
        var state = playerState(player);
        state.putInt("ProbeBreak", started);
        submit(entity, player, player.getInventory().selected,
                player.getMainHandItem(), sequence, target, target, state);
    }

    private static CompoundTag playerState(ServerPlayer player) {
        var state = new CompoundTag();
        state.putUUID("Player", player.getUUID());
        state.putString("Name", player.getGameProfile().getName());
        state.putDouble("X", player.getX());
        state.putDouble("Y", player.getY());
        state.putDouble("Z", player.getZ());
        state.putFloat("Yaw", player.getYRot());
        state.putFloat("Pitch", player.getXRot());
        state.putBoolean("Creative", player.isCreative());
        state.putBoolean("MayBuild", player.getAbilities().mayBuild);
        return state;
    }

    private void submit(long entity, ServerPlayer player, int slot, ItemStack held, int sequence,
                        BlockPos clicked, BlockPos adjacent, CompoundTag state) {
        state.put("Held", held.saveOptional(player.registryAccess()));
        var bytes = encode(state);
        if (bytes == null) {
            player.connection.ackBlockChangesUpTo(sequence);
        } else {
            outstanding.put(entity, new Use(player, slot, held.copy(), sequence, clicked, adjacent, state, null));
            enqueue(entity, clicked, bytes);
            player.resetLastActionTime();
        }
    }

    private void enqueue(long entity, BlockPos target, byte[] bytes) {
        pending.add(ByteBuffer.allocate(2 * Long.BYTES + 2 * Integer.BYTES + bytes.length)
                .putLong(entity).putLong(target.asLong())
                .putInt(target.getX()).putInt(target.getZ()).put(bytes).array());
    }

    byte[] next() {
        return pending.poll();
    }

    static byte[] apply(ServerLevel level, BlockPos target, byte[] request) {
        var state = decode(request);
        if (state == null || !state.hasUUID("Player") || !loaded(level, target) || !owns(target)) {
            return null;
        }
        var player = new PlacementPlayer(level, state);
        if (state.contains("ChestClick")) {
            var result = RemoteTerrainChests.simulate(level, target, player, state.getCompound("ChestClick"));
            if (result == null || player.attemptedDrop) {
                return null;
            }
            var reply = encode(result.response());
            if (reply != null) {
                result.commit();
            }
            return reply;
        }
        var held = ItemStack.parseOptional(level.registryAccess(), state.getCompound("Held"));
        if (state.contains("ProbeBreak")) {
            return encode(response(level, target, target, player, held));
        }
        if (state.contains("DestroyState")) {
            player.setItemInHand(InteractionHand.MAIN_HAND, held);
            destroyBlock(level, target, state, player, held);
            return encode(response(level, target, target, player, held));
        }
        var clicked = BlockPos.of(state.getLong("Clicked"));
        var face = Direction.from3DDataValue(state.getInt("Face"));
        var adjacent = clicked.relative(face);
        if ((!target.equals(clicked) && !target.equals(adjacent)) || !loaded(level, clicked)) {
            return null;
        }
        var hand = state.getBoolean("Offhand") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        player.setItemInHand(hand, held);
        var hit = new BlockHitResult(new Vec3(state.getDouble("HitX"), state.getDouble("HitY"),
                state.getDouble("HitZ")), face, clicked, state.getBoolean("Inside"));
        var context = new UseOnContext(player, hand, hit);
        var clickedState = level.getBlockState(clicked);
        boolean placementOnly = state.getBoolean("PlacementOnly");
        if (!placementOnly && !target.equals(clicked)) {
            return null;
        }
        boolean consumed = !clickedState.getBlock().isEnabled(level.enabledFeatures());
        boolean opensRemoteMenu = false;
        if (!placementOnly && !state.getBoolean("BypassBlock") && !consumed) {
            opensRemoteMenu = clickedState.getMenuProvider(level, clicked) != null;
            if (!opensRemoteMenu) {
                var result = clickedState.useItemOn(held, level, player, hand, hit);
                consumed = result.consumesAction();
                if (result == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                        && hand == InteractionHand.MAIN_HAND) {
                    consumed = clickedState.useWithoutItem(level, player, hit).consumesAction();
                }
            }
        }
        BlockPos redirect = null;
        if (!consumed && !opensRemoteMenu && !state.getBoolean("CoolingDown")
                && !held.isEmpty() && held.isItemEnabled(level.enabledFeatures())) {
            if (held.getItem() instanceof BlockItem) {
                var placement = new BlockPlaceContext(context).getClickedPos();
                if (placementOnly ? placement.equals(target) : owns(placement) && loaded(level, placement)) {
                    useHeldItem(player, held, context);
                } else if (!placementOnly && !owns(placement)) {
                    // Finish on the placement owner after resolving the clicked face.
                    redirect = placement;
                }
            } else if (!placementOnly && owns(adjacent)) {
                useHeldItem(player, held, context);
            }
        }
        var response = response(level, clicked, adjacent, player, held);
        if (opensRemoteMenu) {
            var chest = RemoteTerrainChests.snapshot(level, clicked, player);
            if (chest != null) {
                response.put("OpenChest", chest);
            } else {
                response.putBoolean("RemoteMenu", true);
            }
        }
        if (redirect != null) {
            response.putLong("Placement", redirect.asLong());
        }
        return encode(response);
    }

    private static void useHeldItem(PlacementPlayer player, ItemStack held, UseOnContext context) {
        int count = held.getCount();
        held.useOn(context);
        if (player.isCreative()) {
            held.setCount(count);
        }
    }

    private static CompoundTag response(ServerLevel level, BlockPos clicked, BlockPos adjacent,
                                        PlacementPlayer player, ItemStack held) {
        var response = new CompoundTag();
        response.put("Held", held.saveOptional(level.registryAccess()));
        response.putInt("Clicked", stateId(level, clicked));
        response.putInt("Adjacent", stateId(level, adjacent));
        response.putFloat("Exhaustion", player.exhaustion);
        return response;
    }

    private static void destroyBlock(ServerLevel level, BlockPos target, CompoundTag request,
                                     PlacementPlayer player, ItemStack held) {
        var state = level.getBlockState(target);
        var block = state.getBlock();
        int expected = request.getInt("DestroyState");
        if ((expected >= 0 && Block.getId(state) != expected) || state.isAir()
                || (!player.isCreative() && state.getDestroySpeed(level, target) < 0)
                || !held.getItem().canAttackBlock(state, level, target, player)
                || (block instanceof GameMasterBlock && !request.getBoolean("GameMaster"))) {
            return;
        }
        // These are the terminal vanilla destroy steps. The live player's
        // owner already validated reach, permissions, and mining progress.
        var blockEntity = level.getBlockEntity(target);
        var broken = block.playerWillDestroy(level, target, state, player);
        if (!level.removeBlock(target, false)) {
            return;
        }
        block.destroy(level, target, broken);
        if (!player.isCreative()) {
            var originalTool = held.copy();
            boolean drops = player.hasCorrectToolForDrops(broken);
            held.mineBlock(level, broken, target, player);
            if (drops) {
                block.playerDestroy(level, player, target, broken, blockEntity, originalTool);
            }
        }
    }

    void complete(long entity, byte[] bytes, RemoteTerrainBreaks breaks) {
        var use = outstanding.get(entity);
        if (use == null) {
            return;
        }
        var player = use.player();
        var state = decode(bytes);
        if (use.menu() != null) {
            outstanding.remove(entity);
            reservedInventories.remove(player);
            RemoteTerrainChests.finish(use.menu(), player, use.state().getCompound("ChestClick"), state);
            if (deferredEquipmentDrops.remove(player)) {
                ((ShardPlayers.EquipmentDropAccess) player).atlasnet$dropEquipment();
            }
            return;
        }
        if (use.state().contains("ProbeBreak")) {
            outstanding.remove(entity);
            if (state != null && breaks.confirm(entity, player, use.clicked(), use.sequence(),
                    use.state().getInt("ProbeBreak"), state.getInt("Clicked"), use.held(), this)) {
                return;
            }
            player.connection.ackBlockChangesUpTo(use.sequence());
            return;
        }
        if (state != null) {
            if (state.contains("OpenChest")) {
                RemoteTerrainChests.open(player, use.clicked(), state.getCompound("OpenChest"));
            } else if (state.getBoolean("RemoteMenu")) {
                player.connection.send(new ClientboundSetActionBarTextPacket(
                        Component.literal("Open this block menu from its owning shard.")));
            }
            sendState(player, use.clicked(), state.getInt("Clicked"));
            if (!use.adjacent().equals(use.clicked())) {
                sendState(player, use.adjacent(), state.getInt("Adjacent"));
            }
            if (state.contains("Placement") && !use.state().getBoolean("PlacementOnly")) {
                var destination = BlockPos.of(state.getLong("Placement"));
                if (destination.equals(use.adjacent())) {
                    use.state().putBoolean("PlacementOnly", true);
                    use.state().put("Held", state.getCompound("Held"));
                    var next = encode(use.state());
                    if (next != null) {
                        enqueue(entity, destination, next);
                        return;
                    }
                }
            }
            var inventory = player.getInventory();
            if (ItemStack.matches(inventory.getItem(use.slot()), use.held())) {
                inventory.setItem(use.slot(), ItemStack.parseOptional(player.registryAccess(), state.getCompound("Held")));
                player.containerMenu.broadcastChanges();
            }
            player.causeFoodExhaustion(state.getFloat("Exhaustion"));
        }
        outstanding.remove(entity);
        player.connection.ackBlockChangesUpTo(use.sequence());
    }

    private static boolean owns(BlockPos pos) {
        return ShardChunkIO.ownsTerrain(ChunkPos.asLong(pos));
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos)
                && level.hasChunkAt(pos);
    }

    private static int stateId(ServerLevel level, BlockPos pos) {
        return owns(pos) && loaded(level, pos) ? Block.getId(level.getBlockState(pos)) : -1;
    }

    private static void sendState(ServerPlayer player, BlockPos pos, int state) {
        if (state >= 0) {
            player.connection.send(new ClientboundBlockUpdatePacket(pos, Block.stateById(state)));
        }
    }

    private static byte[] encode(CompoundTag state) {
        try {
            var bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(state, bytes);
            return bytes.size() <= MAX_STATE_BYTES ? bytes.toByteArray() : null;
        } catch (IOException invalid) {
            return null;
        }
    }

    private static CompoundTag decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_STATE_BYTES) {
            return null;
        }
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(128 * 1024));
        } catch (IOException invalid) {
            return null;
        }
    }

    private record Use(ServerPlayer player, int slot, ItemStack held, int sequence,
                       BlockPos clicked, BlockPos adjacent, CompoundTag state,
                       RemoteTerrainChests.RemoteMenu menu) {}

    /** Used only for vanilla placement context. It never joins the level or ticks. */
    private static final class PlacementPlayer extends Player {
        private float exhaustion;
        private boolean attemptedDrop;

        PlacementPlayer(ServerLevel level, CompoundTag state) {
            super(level, level.getSharedSpawnPos(), state.getFloat("Yaw"),
                    new GameProfile(state.getUUID("Player"), state.getString("Name")));
            moveTo(state.getDouble("X"), state.getDouble("Y"), state.getDouble("Z"),
                    state.getFloat("Yaw"), state.getFloat("Pitch"));
            getAbilities().instabuild = state.getBoolean("Creative");
            getAbilities().mayBuild = state.getBoolean("MayBuild");
            setShiftKeyDown(state.getBoolean("Secondary"));
        }

        @Override
        public boolean isCreative() {
            return getAbilities().instabuild;
        }

        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public void causeFoodExhaustion(float amount) {
            exhaustion += amount;
        }

        @Override
        public ItemEntity drop(ItemStack stack, boolean random, boolean trace) {
            attemptedDrop |= !stack.isEmpty();
            return null;
        }
    }
}
