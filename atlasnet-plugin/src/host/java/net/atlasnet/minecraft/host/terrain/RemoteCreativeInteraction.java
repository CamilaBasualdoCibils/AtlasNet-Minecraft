package net.atlasnet.minecraft.host.terrain;

import io.netty.buffer.Unpooled;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.atlasnet.minecraft.server.MinecraftShardBridge;
import net.atlasnet.minecraft.host.entity.PlayerEntityPublication;
import net.atlasnet.minecraft.server.terrain.AuthoritativeTerrainBackend;
import net.atlasnet.minecraft.server.terrain.TerrainStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.GameType;
import net.minecraft.nbt.CompoundTag;
import java.util.concurrent.CompletableFuture;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.atlasnet.atlasnet.Shard;
import org.javagi.base.GErrorException;

/** Runs creative block placement and breaking on the chunk owner. */
public final class RemoteCreativeInteraction {
    private static final String METHOD = "minecraft.terrain.creative-interaction.v3";
    private static final System.Logger LOG = System.getLogger(RemoteCreativeInteraction.class.getName());
    private static final int MAX_QUEUED_INTERACTIONS = 64;
    private static final int ROUTE_ATTEMPTS = 3;
    private static final ClassValue<Boolean> DEFAULT_BLOCK_USE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> blockType) {
            for (var type = blockType; type != BlockBehaviour.class; type = type.getSuperclass()) {
                for (var method : type.getDeclaredMethods()) {
                    if (method.getName().equals("useItemOn") || method.getName().equals("useWithoutItem")) {
                        return false;
                    }
                }
            }
            return true;
        }
    };
    private static final TicketType<UUID> INTERACTION_TICKET =
            TicketType.create("atlasnet_terrain_interaction", UUID::compareTo, 200);

    private final MinecraftServer server;
    private final MinecraftShardBridge bridge;
    private final PlayerEntityPublication publication;
    private final Shard shard;
    private final String world;
    private final AuthoritativeTerrainBackend backend;
    private final Map<ResourceLocation, AuthoritativeTerrainSaves> saves;
    private final ConcurrentLinkedQueue<Runnable> outgoing = new ConcurrentLinkedQueue<>();
    private final HashMap<Long, Pending> pending = new HashMap<>();
    private final HashMap<UUID, CompletableFuture<byte[]>> inFlight = new HashMap<>();
    private final HashSet<UUID> uncertainWrites = new HashSet<>();

    private record Action(BlockPos clicked, BlockPos target, int sequence,
                          ItemStack item, ServerboundUseItemOnPacket use) {}

    private record Pending(ServerPlayer player, ArrayDeque<Packet<?>> packets) {
        Pending(ServerPlayer player) { this(player, new ArrayDeque<>()); }
    }

    public RemoteCreativeInteraction(MinecraftServer server, MinecraftShardBridge bridge, Shard shard,
            String world, AuthoritativeTerrainBackend backend, Map<ResourceLocation, AuthoritativeTerrainSaves> saves,
            PlayerEntityPublication publication)
            throws GErrorException {
        this.server = server;
        this.bridge = bridge;
        this.publication = publication;
        this.shard = shard;
        this.world = world;
        this.backend = backend;
        this.saves = saves;
        if (!shard.bindSpatialPointRpcDeferred(METHOD, (reply, bytes) -> server.execute(() -> {
            try {
                executeOnce(bytes).whenComplete((result, failure) -> {
                    if (failure != null) LOG.log(System.Logger.Level.WARNING, "Remote interaction save failed", failure);
                    reply.complete(failure == null ? result : null);
                });
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "Remote interaction failed", failure);
                reply.complete(null);
            }
        }))) throw new IllegalStateException("Could not bind remote creative interaction");
        bridge.interceptPlayerPackets(this::intercept);
        bridge.onPlayerAttachmentChanged(this::attachmentChanged);
        bridge.deferMigrationDuring(pending::containsKey);
        shard.onPoll(() -> {
            for (Runnable send; (send = outgoing.poll()) != null;) send.run();
        });
    }

    private TerrainStore.ChunkKey key(ResourceLocation dimension, ChunkPos chunk) {
        return new TerrainStore.ChunkKey(world, dimension, chunk.x, chunk.z);
    }

    private void attachmentChanged(long entity, boolean attached) {
        if (!attached) {
            pending.remove(entity);
            return;
        }
        var player = bridge.localPlayer(entity);
        if (player == null) return;
        pending.put(entity, new Pending(player));
        recoverReservation(entity, player, player.level().dimension().location(), 1);
    }

    private void recoverReservation(long entity, ServerPlayer player, ResourceLocation dimension, int delay) {
        backend.loadReservation(world, player.getUUID()).whenComplete((saved, failure) ->
                onAttachedPlayer(entity, player, () -> {
                    if (failure != null) {
                        CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.SECONDS).execute(() ->
                                onAttachedPlayer(entity, player, () -> recoverReservation(entity, player,
                                        dimension, Math.min(delay * 2, 30))));
                    } else if (saved.isEmpty()) {
                        drain(entity, player, dimension);
                    } else {
                        var reservation = saved.get();
                        settle(entity, player, reservation, settlementBytes(reservation), 1,
                                response -> acknowledge(entity, player, reservation, 1,
                                        () -> drain(entity, player, dimension)));
                    }
                }));
    }

    private boolean intercept(long entity, Packet<?> packet) {
        if (!(packet instanceof ServerboundUseItemOnPacket)
                && !(packet instanceof ServerboundUseItemPacket)
                && !(packet instanceof ServerboundPlayerActionPacket)) return false;
        var player = bridge.localPlayer(entity);
        if (player == null) return false;
        var waiting = pending.get(entity);
        if (waiting != null && waiting.player() == player) {
            if (waiting.packets().size() < MAX_QUEUED_INTERACTIONS) waiting.packets().addLast(packet);
            else player.connection.disconnect(net.minecraft.network.chat.Component.literal("Too many pending interactions"));
            return true;
        }
        if (waiting != null) pending.remove(entity);
        if (!player.isCreative()) return false;
        var level = player.serverLevel();
        var action = action(player, packet);
        if (action == null) return false;
        var chunk = new ChunkPos(action.target());
        var dimension = level.dimension().location();
        var key = key(dimension, chunk);
        if (backend.maySave(key)) return false;
        var request = UUID.randomUUID();
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        byte[] payload;
        try {
            buffer.writeUUID(request);
            buffer.writeResourceLocation(dimension);
            buffer.writeBlockPos(action.target());
            buffer.writeBoolean(false);
            ByteBufCodecs.GAME_PROFILE.encode(buffer, player.getGameProfile());
            buffer.writeDouble(player.getX()).writeDouble(player.getY()).writeDouble(player.getZ());
            buffer.writeFloat(player.getYRot()).writeFloat(player.getXRot());
            buffer.writeBoolean(player.isSecondaryUseActive());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, action.item());
            buffer.writeBoolean(action.use() != null);
            if (action.use() != null) ServerboundUseItemOnPacket.STREAM_CODEC.encode(buffer, action.use());
            payload = new byte[buffer.readableBytes()];
            buffer.readBytes(payload);
        } catch (RuntimeException failure) {
            finish(player, action, null);
            return true;
        } finally {
            buffer.release();
        }
        pending.put(entity, new Pending(player));
        reserveAndSend(entity, player, new TerrainStore.Reservation(key, request), action, payload, 1);
        return true;
    }

    private void reserveAndSend(long entity, ServerPlayer player, TerrainStore.Reservation requested,
                                Action action, byte[] payload, int delay) {
        backend.reserveInteraction(player.getUUID(), requested).whenComplete((saved, failure) -> {
            if (failure != null) {
                // The database may have committed before the connection failed.
                // Reuse the request ID and keep later interactions queued.
                CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.SECONDS).execute(() ->
                        onAttachedPlayer(entity, player, () -> reserveAndSend(entity, player, requested,
                                action, payload, Math.min(delay * 2, 30))));
            } else if (requested.equals(saved)) {
                onAttachedPlayer(entity, player, () ->
                        sendReserved(entity, player, requested, action, payload, ROUTE_ATTEMPTS));
            } else {
                // A previous session may have disconnected before learning its result.
                settle(entity, player, saved, settlementBytes(saved), 1,
                        ignored -> onAttachedPlayer(entity, player, () ->
                                acknowledge(entity, player, saved, 1,
                                        () -> reserveAndSend(entity, player, requested, action, payload, 1))));
            }
        });
    }

    private void sendReserved(long entity, ServerPlayer player, TerrainStore.Reservation reservation,
                              Action action, byte[] payload, int attempts) {
        send(reservation.chunk(), payload, response -> {
            if (response != null) {
                confirmed(entity, player, reservation, action, response);
            } else if (attempts > 1) {
                // Routing and chunk authority can change independently while an
                // RPC is in flight. Retry the original request under the same receipt.
                CompletableFuture.delayedExecutor(250, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() ->
                        onAttachedPlayer(entity, player, () ->
                                sendReserved(entity, player, reservation, action, payload, attempts - 1)));
            } else {
                settle(entity, player, reservation, settlementBytes(reservation), 1,
                        settled -> confirmed(entity, player, reservation, action, settled));
            }
        });
    }

    private void onAttachedPlayer(long entity, ServerPlayer player, Runnable action) {
        if (!server.isRunning()) return;
        server.execute(() -> {
            if (!server.isSameThread()) return;
            if (bridge.localPlayer(entity) == player) action.run();
            else {
                var waiting = pending.get(entity);
                if (waiting != null && waiting.player() == player) pending.remove(entity);
            }
        });
    }

    private void send(TerrainStore.ChunkKey key, byte[] payload, java.util.function.Consumer<byte[]> completed) {
        outgoing.add(() -> {
            try {
                // Routing the chunk center agrees with its terrain entity's spatial position.
                shard.callSpatialPointRpcAsync(key.x() * 16.0 + 8, 0, key.z() * 16.0 + 8, METHOD, payload, result -> {
                    byte[] response;
                    try {
                        response = shard.callSpatialPointRpcFinish(result);
                    } catch (GErrorException failure) {
                        response = null;
                    }
                    completed.accept(response);
                });
            } catch (RuntimeException failure) {
                completed.accept(null);
            }
        });
    }

    private static Action action(ServerPlayer player, Packet<?> packet) {
        if (packet instanceof ServerboundPlayerActionPacket digging
                && digging.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            var target = digging.getPos();
            // Breaking needs no block state from the player's replica. The
            // chunk owner will validate the loaded block before executing it.
            if (player.canInteractWithBlock(target, 1.0)) {
                return new Action(target, target, digging.getSequence(), player.getMainHandItem(), null);
            }
        }
        if (!(packet instanceof ServerboundUseItemOnPacket use)) return null;
        var held = player.getItemInHand(use.getHand());
        if (held.isEmpty() || held.getItem().getClass() != BlockItem.class) return null;
        var clicked = use.getHitResult().getBlockPos();
        if (!mayTarget(player, clicked) || !skipsBlockUse(player, clicked)) return null;
        var target = new BlockPlaceContext(player, use.getHand(), held, use.getHitResult()).getClickedPos();
        return new Action(clicked, target, use.getSequence(), held, use);
    }

    private static boolean mayTarget(ServerPlayer player, BlockPos clicked) {
        return player.canInteractWithBlock(clicked, 1.0)
                && player.serverLevel().getChunkSource().getChunkNow(clicked.getX() >> 4, clicked.getZ() >> 4) != null;
    }

    private static boolean skipsBlockUse(ServerPlayer player, BlockPos clicked) {
        // With an item in hand, vanilla secondary use skips the clicked block's interaction.
        if (player.isSecondaryUseActive()) return true;
        var kind = player.serverLevel().getBlockState(clicked).getBlock().getClass();
        return DEFAULT_BLOCK_USE.get(kind);
    }

    private CompletableFuture<byte[]> executeOnce(byte[] bytes) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), server.registryAccess());
        try {
            var id = buffer.readUUID();
            var key = key(buffer.readResourceLocation(), new ChunkPos(buffer.readBlockPos()));
            boolean settle = buffer.readBoolean();
            if (settle && buffer.isReadable()) return CompletableFuture.completedFuture(null);
            var current = inFlight.get(id);
            if (current != null) return current;
            var result = backend.loadInteraction(key, id).thenCompose(receipt -> {
                if (receipt.isPresent()) {
                    server.execute(() -> uncertainWrites.remove(id));
                    return CompletableFuture.completedFuture(receipt.get().interaction().response());
                }
                return server.submit(() -> {
                    // A failed save can follow a live world mutation. Only an operation
                    // that has not mutated the world can be settled as a no-op here.
                    if (uncertainWrites.contains(id)) return CompletableFuture.<byte[]>completedFuture(null);
                    return settle ? backend.rejectInteraction(key, id) : executeLoaded(key, id, bytes);
                })
                        .thenCompose(execution -> execution);
            });
            inFlight.put(id, result);
            result.whenComplete((ignored, failure) -> server.execute(() -> inFlight.remove(id, result)));
            return result;
        } finally {
            buffer.release();
        }
    }

    private CompletableFuture<byte[]> executeLoaded(TerrainStore.ChunkKey key, UUID request, byte[] bytes) {
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, key.dimension()));
        if (level == null || !backend.maySave(key)) return CompletableFuture.completedFuture(null);
        var chunks = level.getChunkSource();
        if (chunks.getChunkNow(key.x(), key.z()) != null) return execute(bytes);
        var position = new ChunkPos(key.x(), key.z());
        chunks.addRegionTicket(INTERACTION_TICKET, position, 0, request);

        // Vanilla's getChunkFuture blocks when called from the game thread.
        // Its off-thread entry point schedules the load on the chunk executor.
        return CompletableFuture.supplyAsync(() -> chunks.getChunkFuture(key.x(), key.z(),
                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true))
                .thenCompose(load -> load)
                .thenCompose(loaded -> server.submit(() ->
                        loaded.isSuccess() ? execute(bytes) : CompletableFuture.<byte[]>completedFuture(null)))
                .whenComplete((execution, failure) -> server.execute(() ->
                        chunks.removeRegionTicket(INTERACTION_TICKET, position, 0, request)))
                .thenCompose(execution -> execution);
    }

    private CompletableFuture<byte[]> execute(byte[] bytes) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), server.registryAccess());
        try {
            var request = buffer.readUUID();
            var dimension = buffer.readResourceLocation();
            var target = buffer.readBlockPos();
            if (buffer.readBoolean()) return CompletableFuture.completedFuture(null);
            var profile = ByteBufCodecs.GAME_PROFILE.decode(buffer);
            var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
            var chunk = new ChunkPos(target);
            var saver = saves.get(dimension);
            var key = key(dimension, chunk);
            if (level == null || saver == null || !backend.maySave(key)
                    || level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) {
                return CompletableFuture.completedFuture(null);
            }
            // Ownership can change between routing and execution. Never create
            // a detached player that would take over a live player's advancements.
            if (server.getPlayerList().getPlayer(profile.getId()) != null) {
                return backend.rejectInteraction(key, request);
            }
            var actor = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
            actor.setPos(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
            actor.setYRot(buffer.readFloat());
            actor.setXRot(buffer.readFloat());
            actor.setShiftKeyDown(buffer.readBoolean());
            var mode = new CompoundTag();
            mode.putInt("playerGameType", GameType.CREATIVE.getId());
            actor.loadGameTypes(mode);
            var item = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            var use = buffer.readBoolean() ? ServerboundUseItemOnPacket.STREAM_CODEC.decode(buffer) : null;
            if (buffer.isReadable() || !actor.isCreative() || !mayTarget(actor, target)
                    || !level.mayInteract(actor, target) || level.isOutsideBuildHeight(target)) {
                return backend.rejectInteraction(key, request);
            }
            var hand = use == null ? net.minecraft.world.InteractionHand.MAIN_HAND : use.getHand();
            // Construct the detached inventory without firing living-entity equip effects.
            var inventory = actor.getInventory();
            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) inventory.items.set(inventory.selected, item);
            else inventory.offhand.set(0, item);
            uncertainWrites.add(request);
            if (use == null) {
                if (level.getBlockState(target).isAir() || !actor.gameMode.destroyBlock(target)) {
                    uncertainWrites.remove(request);
                    return backend.rejectInteraction(key, request);
                }
            } else if (!place(actor, item, use, target)) {
                uncertainWrites.remove(request);
                return backend.rejectInteraction(key, request);
            }

            var result = java.nio.ByteBuffer.allocate(Integer.BYTES)
                    .putInt(Block.getId(level.getBlockState(target))).array();
            return saver.flushInteraction(chunk, new TerrainStore.Interaction(request, result))
                    .thenApply(response -> {
                        server.execute(() -> uncertainWrites.remove(request));
                        return response;
                    });
        } finally {
            buffer.release();
        }
    }

    private void confirmed(long entity, ServerPlayer player, TerrainStore.Reservation reservation,
                           Action action, byte[] response) {
        onAttachedPlayer(entity, player, () -> {
            var dimension = reservation.chunk().dimension();
            if (dimension.equals(player.level().dimension().location())) finish(player, action, response);
            checkpointResult(entity, player, reservation, dimension, 1);
        });
    }

    private void checkpointResult(long entity, ServerPlayer player, TerrainStore.Reservation reservation,
                                  ResourceLocation dimension, int delay) {
        publication.settleTerrain(entity, player, reservation).whenComplete((saved, failure) -> {
            if (failure == null && saved) {
                onAttachedPlayer(entity, player, () -> drain(entity, player, dimension));
                return;
            }
            CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.SECONDS).execute(() ->
                    onAttachedPlayer(entity, player, () -> checkpointResult(entity, player, reservation,
                            dimension, Math.min(delay * 2, 30))));
        });
    }

    private void acknowledge(long entity, ServerPlayer player, TerrainStore.Reservation reservation,
                             int delay, Runnable confirmed) {
        backend.acknowledgeInteraction(player.getUUID(), reservation).whenComplete((released, failure) -> {
            if (failure == null && released) {
                onAttachedPlayer(entity, player, confirmed);
                return;
            }
            CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.SECONDS).execute(() ->
                    onAttachedPlayer(entity, player, () -> acknowledge(entity, player, reservation,
                            Math.min(delay * 2, 30), confirmed)));
        });
    }

    private static byte[] settlementBytes(TerrainStore.Reservation reservation) {
        var key = reservation.chunk();
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUUID(reservation.interactionId());
            buffer.writeResourceLocation(key.dimension());
            buffer.writeBlockPos(new BlockPos(key.x() * 16 + 8, 0, key.z() * 16 + 8));
            buffer.writeBoolean(true);
            var settlement = new byte[buffer.readableBytes()];
            buffer.readBytes(settlement);
            return settlement;
        } finally {
            buffer.release();
        }
    }

    private void settle(long entity, ServerPlayer player, TerrainStore.Reservation reservation,
                        byte[] settlement, int delay, java.util.function.Consumer<byte[]> completed) {
        var key = reservation.chunk();
        send(key, settlement, settled -> {
            if (settled != null) {
                completed.accept(settled);
                return;
            }
            backend.loadInteraction(key, reservation.interactionId()).whenComplete((receipt, failure) -> {
                if (failure == null && receipt.isPresent()) {
                    completed.accept(receipt.get().interaction().response());
                    return;
                }
                CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.SECONDS).execute(() ->
                        onAttachedPlayer(entity, player, () -> settle(entity, player, reservation, settlement,
                                Math.min(delay * 2, 30), completed)));
            });
        });
    }

    private static boolean place(ServerPlayer actor, ItemStack item, ServerboundUseItemOnPacket use,
                                 BlockPos target) {
        var hit = use.getHitResult();
        var clicked = hit.getBlockPos();
        var level = actor.serverLevel();
        var offset = hit.getLocation().subtract(net.minecraft.world.phys.Vec3.atCenterOf(clicked));
        // Match the hit bounds checked by ServerGamePacketListenerImpl.
        if (!(Math.abs(offset.x) < 1.0000001 && Math.abs(offset.y) < 1.0000001
                && Math.abs(offset.z) < 1.0000001)) return false;
        // The clicked face can belong to a loaded replica. This path only places
        // an ordinary BlockItem and verifies that vanilla will write the owned target.
        if (item.getItem().getClass() != BlockItem.class
                || !item.isItemEnabled(level.enabledFeatures()) || level.isOutsideBuildHeight(clicked)
                || !mayTarget(actor, clicked) || !level.mayInteract(actor, clicked)
                || !skipsBlockUse(actor, clicked)) return false;
        var context = new BlockPlaceContext(actor, use.getHand(), item, hit);
        return target.equals(context.getClickedPos()) && ((BlockItem) item.getItem()).place(context).consumesAction();
    }

    private void drain(long entity, ServerPlayer player, ResourceLocation dimension) {
        var current = pending.get(entity);
        if (current == null || current.player() != player) return;
        pending.remove(entity);
        if (!dimension.equals(player.level().dimension().location())) return;
        var waiting = current.packets();
        while (!waiting.isEmpty()) {
            var packet = waiting.removeFirst();
            if (!intercept(entity, packet)) handleLocally(player, packet);
        }
    }

    private static void handleLocally(ServerPlayer player, Packet<?> packet) {
        if (packet instanceof ServerboundUseItemOnPacket use) use.handle(player.connection);
        else if (packet instanceof ServerboundUseItemPacket use) use.handle(player.connection);
        else if (packet instanceof ServerboundPlayerActionPacket action) action.handle(player.connection);
    }

    private static void finish(ServerPlayer player, Action action, byte[] response) {
        var level = player.serverLevel();
        if (!action.clicked().equals(action.target())) {
            player.connection.send(new ClientboundBlockUpdatePacket(level, action.clicked()));
        }
        var state = response != null && response.length == Integer.BYTES
                ? Block.stateById(java.nio.ByteBuffer.wrap(response).getInt()) : level.getBlockState(action.target());
        player.connection.send(new ClientboundBlockUpdatePacket(action.target(), state));
        player.connection.ackBlockChangesUpTo(action.sequence());
    }
}
