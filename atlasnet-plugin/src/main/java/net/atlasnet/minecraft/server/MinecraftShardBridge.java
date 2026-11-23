package net.atlasnet.minecraft.server;

import com.mojang.authlib.GameProfile;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.Objects;
import java.util.HashSet;
import java.util.function.BiConsumer;
import net.atlasnet.minecraft.sdk.core.MinecraftIngressPackets;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.atlasnet.atlasnet.Shard;
import org.atlasnet.atlasnet.Signal;
import org.atlasnet.atlasnet.Transform;
import org.javagi.base.GErrorException;

/** Attaches the existing Minecraft server in the shard to AtlasNet's provider callbacks. */
public final class MinecraftShardBridge {
    private static final long MAX_QUEUED_BYTES = 16L * 1024 * 1024;
    private static final System.Logger LOG = System.getLogger(MinecraftShardBridge.class.getName());
    private static final java.util.Map<MinecraftServer, java.lang.ref.WeakReference<MinecraftShardBridge>> CHAT_BRIDGES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final MinecraftServer server;
    private final Shard shard;
    private final net.minecraft.network.ProtocolInfo<net.minecraft.network.protocol.game.ClientGamePacketListener>
            activityProtocol;
    // The game thread owns connection changes. AtlasNet can check membership on its worker.
    private final ConcurrentHashMap<Long, AtlasNetConnection> connections = new ConcurrentHashMap<>();
    private final HashSet<Long> confirmedPlayers = new HashSet<>();
    private final HashSet<Long> announcedPlayers = new HashSet<>();
    private final HashSet<Long> frozenPlayers = new HashSet<>();
    private final HashMap<Long, Long> clients = new HashMap<>();
    private final ConcurrentHashMap<Long, Position> transforms = new ConcurrentHashMap<>();
    private final HashMap<Long, Position> lastSampledPositions = new HashMap<>();
    private final HashMap<Long, PlayerPose> lastPlayerPoses = new HashMap<>();
    private final HashMap<Long, PlayerAppearance> lastAppearances = new HashMap<>();
    private final ConcurrentHashMap<Long, PlayerActivity> playerActivity = new ConcurrentHashMap<>();
    private volatile int playerActivityRadius;
    private final BiConsumer<Long, ServerPlayer> playerSnapshot;
    private final java.util.function.LongConsumer playerRetired;
    private BiConsumer<Long, Boolean> attachmentChanged = (entity, attached) -> {};
    private java.util.function.BiPredicate<Long, net.minecraft.network.protocol.Packet<?>> packetInterceptor =
            (entity, packet) -> false;
    private java.util.function.LongPredicate pendingInteraction = entity -> false;
    private java.util.function.LongFunction<ObserverState> captureObserver = entity -> ObserverState.EMPTY;
    private BiConsumer<Long, ObserverState> restoreObserver = (entity, state) -> {};
    private java.util.function.LongConsumer detachObserver = entity -> {};
    private int ticks;
    private final ArrayDeque<Outbound> outgoing = new ArrayDeque<>();
    private long queuedBytes;

    public record PlayerTransfer(long client, int playId, GameProfile profile, ClientInformation information,
                                 InetSocketAddress peer, ObserverState observer) {}

    public record ObserverState(String interest, java.util.Map<Long, java.util.UUID> visiblePlayers) {
        public static final ObserverState EMPTY = new ObserverState("", java.util.Map.of());

        public ObserverState {
            visiblePlayers = java.util.Map.copyOf(visiblePlayers);
        }
    }

    public void onObserverTransfer(java.util.function.LongFunction<ObserverState> capture,
                                   BiConsumer<Long, ObserverState> restore,
                                   java.util.function.LongConsumer detach) {
        captureObserver = capture;
        restoreObserver = restore;
        detachObserver = detach;
    }
    private record Position(double x, double y, double z) {}
    private record PlayerPose(Position position, float yaw, float pitch, float headYaw, boolean grounded) {}
    private record PlayerActivity(Position position, byte[] pose, byte[] head) {}
    private record PlayerAppearance(java.util.List<net.minecraft.world.item.ItemStack> equipment, PlayerMetadataState metadata) {}

    public record PlayerMetadataState(byte livingFlags, byte sharedFlags, net.minecraft.world.entity.Pose pose) {
        public static PlayerMetadataState capture(ServerPlayer player) {
            var data = player.getEntityData();
            return new PlayerMetadataState(data.get(PlayerMetadata.LIVING_FLAGS),
                    data.get(PlayerMetadata.SHARED_FLAGS), player.getPose());
        }

        public net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket packet(int entity) {
            return new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(entity, java.util.List.of(
                    net.minecraft.network.syncher.SynchedEntityData.DataValue.create(PlayerMetadata.LIVING_FLAGS, livingFlags),
                    net.minecraft.network.syncher.SynchedEntityData.DataValue.create(PlayerMetadata.SHARED_FLAGS, sharedFlags),
                    net.minecraft.network.syncher.SynchedEntityData.DataValue.create(PlayerMetadata.POSE, pose)));
        }
    }

    // Read vanilla metadata without consuming its dirty entries.
    private abstract static class PlayerMetadata extends ServerPlayer {
        private PlayerMetadata(MinecraftServer server, net.minecraft.server.level.ServerLevel level,
                               GameProfile profile, ClientInformation info) {
            super(server, level, profile, info);
        }

        static final net.minecraft.network.syncher.EntityDataAccessor<Byte> LIVING_FLAGS = DATA_LIVING_ENTITY_FLAGS;
        static final net.minecraft.network.syncher.EntityDataAccessor<Byte> SHARED_FLAGS = DATA_SHARED_FLAGS_ID;
        static final net.minecraft.network.syncher.EntityDataAccessor<net.minecraft.world.entity.Pose> POSE = DATA_POSE;
    }

    public void setPlayerActivityRadius(int radius) {
        playerActivityRadius = radius;
    }

    private record Outbound(long client, long entity, byte[] packet, Position audienceCenter) {
        Outbound(long client, long entity, byte[] packet) { this(client, entity, packet, null); }
    }

    public MinecraftShardBridge(MinecraftServer server, Shard shard) {
        this(server, shard, (id, player) -> {}, id -> {});
    }

    public MinecraftShardBridge(MinecraftServer server, Shard shard,
                                BiConsumer<Long, ServerPlayer> playerSnapshot,
                                java.util.function.LongConsumer playerRetired) {
        this.server = Objects.requireNonNull(server, "server");
        this.playerSnapshot = playerSnapshot;
        this.playerRetired = playerRetired;
        this.shard = Objects.requireNonNull(shard, "shard");
        activityProtocol = net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                net.minecraft.network.RegistryFriendlyByteBuf.decorator(server.registryAccess()));
        shard.onIsEntityRegistered(connections::containsKey);
        shard.onClientSpawn((client, entity, placement, data) -> {
            var initial = placement == null ? null : new MinecraftPlayerJoin.InitialSpawn(
                    net.minecraft.resources.ResourceLocation.parse(placement.readWorldName()),
                    placement.readX(), placement.readY(), placement.readZ());
            return spawn(client, entity, initial, data);
        });
        shard.onCommand(command -> {
            long entity = command.readEntity();
            byte[] payload = command.getPayload();
            server.execute(() -> {
                if (frozenPlayers.contains(entity)) return;
                var connection = connections.get(entity);
                if (connection != null) {
                    // Routed Play commands arrive only after AtlasNet publishes ownership.
                    if (confirmedPlayers.add(entity)) {
                        attachmentChanged.accept(entity, true);
                        if (announcedPlayers.add(entity)
                                && connection.getPacketListener() instanceof ServerGamePacketListenerImpl play) {
                            announce("multiplayer.player.joined", play.player);
                        }
                    }
                    connection.receivePacket(payload);
                }
            });
        });
        shard.onUnregisterEntity(entity -> server.execute(() -> remove(entity)));
        shard.onPoll(this::flush);
        CHAT_BRIDGES.put(server, new java.lang.ref.WeakReference<>(this));

    }

    private boolean spawn(long client, long entity, MinecraftPlayerJoin.InitialSpawn initial, byte[] data) {
        try {
            if (server.isSameThread()) {
                return place(client, entity, initial, data);
            }
            return server.submit(() -> place(client, entity, initial, data)).join();
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not initialize the authenticated Minecraft player", failure);
            return false;
        }
    }

    private boolean place(long client, long entity, MinecraftPlayerJoin.InitialSpawn initial, byte[] data) {
        if (connections.containsKey(entity)) {
            return false;
        }
        var connection = MinecraftPlayerJoin.place(server, initial, data,
                packet -> enqueue(new Outbound(client, entity, packet)));
        connection.interceptPlayPackets(packet -> intercept(entity, packet));
        connections.put(entity, connection);
        clients.put(entity, client);
        return true;
    }

    private void remove(long entity) {
        transforms.remove(entity);
        lastSampledPositions.remove(entity);
        lastPlayerPoses.remove(entity);
        lastAppearances.remove(entity);
        playerActivity.remove(entity);
        clients.remove(entity);
        frozenPlayers.remove(entity);
        var connection = connections.remove(entity);
        if (connection != null) {
            attachmentChanged.accept(entity, false);
            if (announcedPlayers.remove(entity)
                    && connection.getPacketListener() instanceof ServerGamePacketListenerImpl play) {
                announce("multiplayer.player.left", play.player);
            }
            if (confirmedPlayers.remove(entity)) {
                if (connection.getPacketListener() instanceof ServerGamePacketListenerImpl play) {
                    playerSnapshot.accept(entity, play.player);
                }
                playerRetired.accept(entity);
            }
            connection.closeFromAtlasNet(new DisconnectionDetails(Component.literal("Disconnected")));
        }
    }

    private void announce(String key, ServerPlayer player) {
        var message = Component.translatable(key, player.getDisplayName()).withStyle(net.minecraft.ChatFormatting.YELLOW);
        var packet = new net.minecraft.network.protocol.game.ClientboundSystemChatPacket(message, false);
        enqueue(new Outbound(0, 0, encodePlayPacket(0, packet)));
    }

    /** Called after vanilla validates and filters a player's chat message. */
    public static boolean broadcastPlayerChat(net.minecraft.network.chat.PlayerChatMessage message,
            ServerPlayer sender, net.minecraft.network.chat.ChatType.Bound chatType) {
        var server = sender.getServer();
        var reference = CHAT_BRIDGES.get(server);
        var bridge = reference == null ? null : reference.get();
        if (bridge == null) return false;
        server.execute(() -> {
            var mask = message.filterMask();
            var content = mask.isEmpty() ? message.decoratedContent() : mask.applyWithFormatting(message.signedContent());
            if (content == null) return;
            server.logChatMessage(content, chatType, null);
            var packet = new net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket(content, chatType);
            bridge.enqueue(new Outbound(0, 0, bridge.encodePlayPacket(0, packet)));
        });
        return true;
    }

    /** Run once per Minecraft server tick so vanilla maintains its connection lifecycle. */
    public void tickConnections() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Minecraft connections require the server thread");
        }
        boolean capture = ++ticks % 20 == 0;
        for (var entry : connections.entrySet()) {
            if (frozenPlayers.contains(entry.getKey())) continue;
            var connection = entry.getValue();
            connection.tick();
            connection.tickInput();
            if (confirmedPlayers.contains(entry.getKey()) && connection.isConnected()
                    && connection.getPacketListener() instanceof ServerGamePacketListenerImpl play) {
                var position = new Position(play.player.getX(), play.player.getY(), play.player.getZ());
                if (!position.equals(lastSampledPositions.put(entry.getKey(), position))) {
                    transforms.put(entry.getKey(), position);
                }
                if (playerActivityRadius > 0) sampleActivity(entry.getKey(), play.player, position);
                if (capture) playerSnapshot.accept(entry.getKey(), play.player);
            }
        }
    }

    private void sampleActivity(long entity, ServerPlayer player, Position position) {
        long client = clients.get(entity);
        sampleAppearance(entity, client, player, position);
        var pose = new PlayerPose(position, player.getYRot(), player.getXRot(), player.getYHeadRot(), player.onGround());
        var previous = lastPlayerPoses.put(entity, pose);
        if (pose.equals(previous)) return;
        var movement = new net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket(player);
        var head = new net.minecraft.network.protocol.game.ClientboundRotateHeadPacket(player,
                (byte) (pose.headYaw() * 256.0f / 360.0f));
        playerActivity.put(entity, new PlayerActivity(position, encodePlayPacket(client, movement),
                encodePlayPacket(client, head)));
    }

    private void sampleAppearance(long entity, long client, ServerPlayer player, Position position) {
        var slots = net.minecraft.world.entity.EquipmentSlot.values();
        var previous = lastAppearances.get(entity);
        var equipment = new java.util.ArrayList<net.minecraft.world.item.ItemStack>(slots.length);
        var changed = new java.util.ArrayList<com.mojang.datafixers.util.Pair<
                net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack>>();
        for (int i = 0; i < slots.length; i++) {
            var item = player.getItemBySlot(slots[i]);
            if (previous == null || !net.minecraft.world.item.ItemStack.matches(previous.equipment().get(i), item)) {
                item = item.copy();
                changed.add(com.mojang.datafixers.util.Pair.of(slots[i], item));
            } else {
                item = previous.equipment().get(i);
            }
            equipment.add(item);
        }
        var metadata = PlayerMetadataState.capture(player);
        if (!changed.isEmpty()) {
            var packet = new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(player.getId(), changed);
            enqueue(new Outbound(client, entity, encodePlayPacket(client, packet), position));
        }
        if (previous == null || !metadata.equals(previous.metadata())) {
            var packet = metadata.packet(player.getId());
            enqueue(new Outbound(client, entity, encodePlayPacket(client, packet), position));
        }
        if (previous == null || !changed.isEmpty() || !metadata.equals(previous.metadata())) {
            lastAppearances.put(entity, new PlayerAppearance(equipment, metadata));
        }
    }

    private byte[] encodePlayPacket(long excludedClient, net.minecraft.network.protocol.Packet<
            ? super net.minecraft.network.protocol.game.ClientGamePacketListener> packet) {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                server.registryAccess());
        try {
            if (excludedClient != 0) buffer.writeLongLE(excludedClient);
            activityProtocol.codec().encode(buffer, packet);
            var data = new byte[buffer.readableBytes()];
            buffer.readBytes(data);
            return data;
        } finally {
            buffer.release();
        }
    }

    private boolean intercept(long entity, net.minecraft.network.protocol.Packet<?> packet) {
        if (packetInterceptor.test(entity, packet)) return true;
        if (playerActivityRadius > 0
                && packet instanceof net.minecraft.network.protocol.game.ServerboundSwingPacket swing) {
            var player = localPlayer(entity);
            if (player != null) {
                int animation = swing.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND ? 0 : 3;
                long client = clients.get(entity);
                var position = new Position(player.getX(), player.getY(), player.getZ());
                var update = new net.minecraft.network.protocol.game.ClientboundAnimatePacket(player, animation);
                enqueue(new Outbound(client, entity, encodePlayPacket(client, update), position));
            }
        }
        return false;
    }

    /** Capture the last live state on the game thread before vanilla closes the world. */
    public void captureBeforeShutdown() {
        for (long id : confirmedPlayers) {
            var player = localPlayer(id);
            if (player == null) continue;
            playerSnapshot.accept(id, player);
            playerRetired.accept(id);
        }
    }

    /** Resolves a locally attached player on the Minecraft server thread. */
    public ServerPlayer localPlayer(long entity) {
        if (!server.isSameThread()) throw new IllegalStateException("Entity lookup requires the server thread");
        var connection = connections.get(entity);
        return !frozenPlayers.contains(entity) && confirmedPlayers.contains(entity) &&
                connection != null && connection.isConnected()
                && connection.getPacketListener() instanceof ServerGamePacketListenerImpl play ? play.player : null;
    }

    /** Reports a routed Play session and its detachment on the Minecraft thread. */
    public void onPlayerAttachmentChanged(BiConsumer<Long, Boolean> callback) {
        attachmentChanged = attachmentChanged.andThen(Objects.requireNonNull(callback));
    }

    public void interceptPlayerPackets(
            java.util.function.BiPredicate<Long, net.minecraft.network.protocol.Packet<?>> callback) {
        packetInterceptor = Objects.requireNonNull(callback);
    }

    public void deferMigrationDuring(java.util.function.LongPredicate test) {
        pendingInteraction = Objects.requireNonNull(test);
    }

    public boolean hasAttachedPlayer(long entity) {
        return connections.containsKey(entity);
    }

    public void sendObservedPacket(long observer, net.minecraft.network.protocol.Packet<?> packet) {
        if (!server.isSameThread()) throw new IllegalStateException("Observation requires the server thread");
        var connection = connections.get(observer);
        if (connection != null) connection.send(packet);
    }

    public long localId(ServerPlayer player) {
        for (long entity : confirmedPlayers) {
            if (localPlayer(entity) == player) return entity;
        }
        return 0;
    }

    public long localClient(long entity) {
        return localPlayer(entity) == null ? 0 : clients.getOrDefault(entity, 0L);
    }

    /** Freeze the source connection's vanilla listener while AtlasNet holds incoming commands. */
    public PlayerTransfer freezePlayer(long entity) {
        var player = localPlayer(entity);
        // The live menu owns its cursor stack and container transaction state.
        if (player == null || player.isPassenger() || player.isVehicle() || pendingInteraction.test(entity)
                || player.containerMenu != player.inventoryMenu)
            return null;
        var connection = connections.get(entity);
        if (connection.hasPendingInput() || !(connection.getRemoteAddress() instanceof InetSocketAddress peer))
            return null;
        var client = clients.get(entity);
        if (client == null) return null;
        frozenPlayers.add(entity);
        transforms.remove(entity);
        playerActivity.remove(entity);
        return new PlayerTransfer(client, player.getId(), player.getGameProfile(), player.clientInformation(),
                peer, captureObserver.apply(entity));
    }

    public void resumePlayer(long entity) {
        lastSampledPositions.remove(entity);
        lastPlayerPoses.remove(entity);
        frozenPlayers.remove(entity);
    }

    /** Dispose the source-side embedded channel after commit; the endpoint keeps the TCP session. */
    public void detachPlayer(long entity) {
        confirmedPlayers.remove(entity);
        announcedPlayers.remove(entity);
        detachObserver.accept(entity);
        remove(entity);
    }

    public boolean canRestorePlayer(long entity, PlayerTransfer transfer) {
        return !connections.containsKey(entity) &&
                server.getPlayerList().getPlayer(transfer.profile().getId()) == null &&
                server.getPlayerList().getPlayers().stream().noneMatch(player -> player.getId() == transfer.playId()) &&
                server.getPlayerList().canPlayerLogin(transfer.peer(), transfer.profile()) == null;
    }

    /** Complete the destination's vanilla attachment before AtlasNet publishes routing to it. */
    public boolean restorePlayer(long entity, PlayerTransfer transfer, CompoundTag state) {
        if (!canRestorePlayer(entity, transfer)) return false;
        long client = transfer.client();
        var connection = MinecraftPlayerJoin.resumePlay(server, transfer.profile(), transfer.information(),
                transfer.peer(), transfer.playId(), state, packet -> enqueue(new Outbound(client, entity, packet)));
        connection.interceptPlayPackets(packet -> intercept(entity, packet));
        connections.put(entity, connection);
        clients.put(entity, client);
        announcedPlayers.add(entity);
        restoreObserver.accept(entity, transfer.observer());
        return true;
    }

    private synchronized void enqueue(Outbound output) {
        long cost = output.packet().length + 32L;
        if (cost > MAX_QUEUED_BYTES - queuedBytes) {
            throw new IllegalStateException("Minecraft shard packet queue is full");
        }
        outgoing.addLast(output);
        queuedBytes += cost;
    }

    private synchronized Outbound next() {
        var output = outgoing.pollFirst();
        if (output != null) {
            queuedBytes -= output.packet().length + 32L;
        }
        return output;
    }

    public static void dispatchPlayPacket(Shard shard, long client, long entity, byte[] packet)
            throws GErrorException {
        var signal = new Signal();
        signal.writeRecipient(client);
        signal.writeEntity(entity);
        signal.writeSignal(MinecraftIngressPackets.PLAY_PACKET_SIGNAL);
        signal.setPayload(packet);
        shard.dispatchRawSignals(new Signal[]{signal});
    }

    private void flush() {
        try {
            for (int i = 0; i < 512; i++) {
                var output = next();
                if (output == null) break;
                if (output.audienceCenter() == null) {
                    if (output.client() == 0) {
                        var signal = new Signal();
                        signal.writeAudience(org.atlasnet.atlasnet.Audience.AUDIENCE_BROADCAST);
                        signal.writeSignal(MinecraftIngressPackets.PLAY_PACKET_SIGNAL);
                        signal.setPayload(output.packet());
                        shard.dispatchRawSignals(new Signal[]{signal});
                    } else {
                        dispatchPlayPacket(shard, output.client(), output.entity(), output.packet());
                    }
                } else {
                    dispatchActivity(output.audienceCenter(), output.packet());
                }
            }
            for (var sampled : transforms.entrySet()) {
                long entity = sampled.getKey();
                var position = sampled.getValue();
                if (transforms.get(entity) != position) continue;
                var transform = new Transform();
                transform.writeX(position.x());
                transform.writeY(position.y());
                transform.writeZ(position.z());
                shard.updateEntityTransform(entity, transform);
                transforms.remove(entity, position);
            }
            for (var entry : playerActivity.entrySet()) {
                var activity = entry.getValue();
                if (!playerActivity.remove(entry.getKey(), activity)) continue;
                var position = activity.position();
                dispatchActivity(position, activity.pose());
                dispatchActivity(position, activity.head());
            }
            shard.flushSignals();
        } catch (GErrorException failure) {
            LOG.log(System.Logger.Level.ERROR, "Could not send Minecraft Play Signal", failure);
        }
    }

    private void dispatchActivity(Position center, byte[] packet) throws GErrorException {
        shard.dispatchSpatialSignal(center.x(), center.y(), center.z(), playerActivityRadius,
                (byte) 3, MinecraftIngressPackets.PLAYER_ACTIVITY_SIGNAL, packet);
    }
}
