package net.atlasnet.minecraft.fabric.server;

import com.mojang.authlib.GameProfile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.atlasnet.minecraft.atlasnet.protocol.commands.BlockInteractionCommand;
import net.atlasnet.minecraft.atlasnet.protocol.commands.GameplayInputCommand;
import net.atlasnet.minecraft.atlasnet.protocol.commands.PlayerInputCommand;
import net.atlasnet.minecraft.atlasnet.world.AtlasNetWorlds;
import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.atlasnet.minecraft.atlasnet.protocol.ProtocolRegistry;
import net.atlasnet.minecraft.atlasnet.protocol.signals.ChatSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerJoinedSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerLeftSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.TerrainUpdateSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.ShardMetricsSignal;
import net.atlasnet.minecraft.fabric.mixin.ServerConnectionAccessor;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.ArrayList;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerRegionSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerRegionSignal.Point;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerRegionSignal.Plane;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerRegionSignal.Shape;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.server.network.config.SynchronizeRegistriesTask;
import net.minecraft.world.flag.FeatureFlags;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;

public final class ShardPlayers {
    private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.parse(AtlasNetWorlds.OVERWORLD_DIMENSION));
    private static volatile DedicatedServer server;
    private static final Map<Long, ServerPlayer> players = new HashMap<>();
    private static final Set<ServerPlayer> frozen = new HashSet<>();
    private static final Map<Long, StagedPlayer> staged = new HashMap<>();
    private static final int MAX_PLAYER_NBT = 2 * 1024 * 1024;
    private static ServerPlayer loadingTransfer;
    private static CompoundTag transferTag;
    private static final Map<Long, ShardConnection> connections = new ConcurrentHashMap<>();
    private static final ChunkDemandManager chunkDemand = new ChunkDemandManager();
    private static final RemoteTerrainBreaks remoteTerrainBreaks = new RemoteTerrainBreaks();
    private static final CrossShardMelee crossShardMelee = new CrossShardMelee();

    private ShardPlayers() {
    }

    public static int trackingRange() {
        return Math.min(net.minecraft.world.entity.EntityType.PLAYER.clientTrackingRange(),
                server.getPlayerList().getViewDistance()) * 16;
    }

    public static void ready(DedicatedServer dedicatedServer) {
        server = dedicatedServer;
    }

    public static int[] takeChunkLifecycle() {
        return ShardChunkIO.takeLifecycle();
    }

    public static void revokeTerrain() {
        ShardChunkIO.revoke();
    }

    public static boolean terrainRegion(double[] bounds) {
        var current = server;
        if (current == null) {
            return false;
        }
        current.submit(() -> ShardChunkIO.current().updateRegion(bounds)).join();
        return true;
    }

    public static void terrainVacated(int x, int z, boolean previousOwner) {
        var current = server;
        if (current != null) {
            var pos = new ChunkPos(x, z);
            current.submit(() -> {
                if (previousOwner) {
                    ShardChunkIO.current().release(pos);
                } else {
                    ShardChunkIO.current().available(pos);
                }
            }).join();
        }
    }

    public static byte[] prepareChunk(int x, int z, String transfer) {
        var current = server;
        return current == null ? null
                : ShardChunkIO.current().prepare(current, new ChunkPos(x, z), UUID.fromString(transfer));
    }

    public static boolean stageChunk(String transfer, byte[] checkpoint) {
        var current = server;
        return current != null && ShardChunkIO.current().stage(current, UUID.fromString(transfer), checkpoint);
    }

    public static boolean commitChunk(String transfer) {
        return ShardChunkIO.current().commitRestore(server, UUID.fromString(transfer), ShardPlayers::publishChunk);
    }

    public static void extractChunk(int x, int z, String transfer, boolean commit) {
        var current = server;
        if (current == null) {
            return;
        }
        var io = ShardChunkIO.current();
        var pos = new ChunkPos(x, z);
        var id = UUID.fromString(transfer);
        if (commit) {
            io.commitExtract(current, pos, id);
        } else {
            io.abortExtract(current, pos, id);
        }
    }

    public static void abortChunk(String transfer) {
        ShardChunkIO.current().abortRestore(server, UUID.fromString(transfer));
    }

    public static void publishChunk(net.minecraft.server.level.ServerLevel level,
                                     net.minecraft.world.level.chunk.LevelChunk chunk) {
        var cache = level.getChunkSource();
        var pos = chunk.getPos();
        var viewers = cache.chunkMap.getPlayers(pos, false);
        if (viewers.isEmpty() && !chunkDemand.remoteViewersPresent(pos)) {
            return;
        }
        var packet = new ClientboundLevelChunkWithLightPacket(chunk, cache.getLightEngine(), null, null);
        for (var observer : viewers) {
            observer.connection.send(packet);
        }
        chunkDemand.sendRemoteTerrain((DedicatedServer) level.getServer(), pos, packet);
    }

    public static int[] takeChunkDemand(long entityId) {
        var connection = connections.get(entityId);
        if (connection == null) {
            return null;
        }
        var view = connection.demand.getAndSet(null);
        return view == null ? null : new int[] {view.x(), view.z(), view.view(), view.simulation()};
    }

    public static void applyChunkDemand(long entity, long revision, int x, int z,
                                        int view, int simulation, boolean[] owned, boolean refresh) {
        DedicatedServer current = server;
        if (current != null && current.isRunning()) {
            current.execute(() -> chunkDemand.apply(current, entity, revision,
                    x, z, view, simulation, owned, !players.containsKey(entity), refresh));
        }
    }

    public static byte[] nextTerrainSnapshot() {
        return chunkDemand.nextSnapshot();
    }

    public static boolean remoteTerrainViewers(ChunkPos pos) {
        return chunkDemand.remoteViewersPresent(pos);
    }

    public static void sendRemoteTerrain(ChunkPos pos, Packet<?> packet) {
        chunkDemand.sendRemoteTerrain(server, pos, packet);
    }

    public static boolean spawn(long clientId, long entityId, String dimension, double x, double y, double z) {
        DedicatedServer current = server;
        if (current == null || !current.isRunning() || !AtlasNetWorlds.OVERWORLD_DIMENSION.equals(dimension)) {
            return false;
        }
        return current.submit(() -> {
            if (players.containsKey(entityId)) {
                return true;
            }
            var profile = profile(clientId);
            var cookie = CommonListenerCookie.createInitial(profile, false);
            var player = new ServerPlayer(current, current.getLevel(OVERWORLD), profile, cookie.clientInformation());
            player.moveTo(x, y, z, 0, 0);
            var connection = new ShardConnection(current);
            connection.configure(current);
            current.getPlayerList().placeNewPlayer(connection, player, cookie);
            connection.recordPose(player);
            connections.put(entityId, connection);
            players.put(entityId, player);
            return true;
        }).join();
    }

    private static GameProfile profile(long clientId) {
        return new GameProfile(new UUID(0x41544c41534e4554L, clientId), displayName(clientId));
    }

    public static byte[] freezePlayer(long entityId, int maxBytes) {
        return server.submit(() -> {
            var player = players.get(entityId);
            if (player == null || !player.isAlive() || player.isPassenger() || player.isVehicle()
                    || player.isSleeping()) {
                return null;
            }
            try {
                var data = player.saveWithoutId(new CompoundTag());
                var bytes = new ByteArrayOutputStream();
                NbtIo.writeCompressed(data, bytes);
                if (bytes.size() > maxBytes) {
                    return null;
                }
                connections.get(entityId).recordPose(player);
                frozen.add(player);
                return bytes.toByteArray();
            } catch (IOException error) {
                return null;
            }
        }).join();
    }

    public static boolean stagePlayer(long clientId, long entityId, byte[] data) {
        DedicatedServer current = server;
        if (current == null || !current.isRunning()) {
            return false;
        }
        return current.submit(() -> {
            if (players.containsKey(entityId)) {
                return false;
            }
            try {
                var tag = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(MAX_PLAYER_NBT));
                var profile = profile(clientId);
                var cookie = CommonListenerCookie.createInitial(profile, false);
                var player = new ServerPlayer(current, current.overworld(), profile, cookie.clientInformation());
                if (!tag.hasUUID("UUID") || !profile.getId().equals(tag.getUUID("UUID"))) {
                    return false;
                }
                player.load(tag);
                staged.put(entityId, new StagedPlayer(player, cookie, tag));
                return true;
            } catch (IOException | RuntimeException error) {
                return false;
            }
        }).join();
    }

    public static boolean activatePlayer(long entityId) {
        return server.submit(() -> {
            var incoming = staged.get(entityId);
            if (incoming == null) {
                return players.containsKey(entityId);
            }
            var connection = new ShardConnection(server);
            loadingTransfer = incoming.player();
            transferTag = incoming.tag();
            try {
                server.getPlayerList().placeNewPlayer(connection, incoming.player(), incoming.cookie());
            } finally {
                loadingTransfer = null;
                transferTag = null;
            }
            connection.recordPose(incoming.player());
            connections.put(entityId, connection);
            players.put(entityId, incoming.player());
            staged.remove(entityId);
            return true;
        }).join();
    }

    public static CompoundTag transferData(ServerPlayer player) {
        return player == loadingTransfer ? transferTag : null;
    }

    public static boolean isFrozen(ServerPlayer player) {
        return frozen.contains(player);
    }

    public static void abortPlayerTransfer(long entityId, boolean source) {
        server.submit(() -> {
            if (source) {
                frozen.remove(players.get(entityId));
            } else {
                staged.remove(entityId);
            }
        }).join();
    }

    public static boolean extractPlayer(long entityId) {
        return server.submit(() -> {
            var player = players.get(entityId);
            if (player == null) {
                return true;
            }
            if (!isFrozen(player)) {
                return false;
            }
            server.getPlayerList().remove(player);
            players.remove(entityId);
            connections.remove(entityId);
            frozen.remove(player);
            crossShardMelee.discard(player);
            return true;
        }).join();
    }

    private record StagedPlayer(ServerPlayer player, CommonListenerCookie cookie, CompoundTag tag) {}

    public static void remove(long entityId) {
        DedicatedServer current = server;
        if (current == null || !current.isRunning()) {
            return;
        }
        connections.remove(entityId);
        current.execute(() -> {
            ServerPlayer player = players.remove(entityId);
            if (player != null) {
                frozen.remove(player);
                crossShardMelee.discard(player);
                current.getPlayerList().remove(player);
            }
        });
    }

    public static byte[] prepareMelee(long entity) {
        return server.submit(() -> crossShardMelee.prepare(players.get(entity))).join();
    }

    public static byte[] applyMelee(long target, byte[] state) {
        return server.submit(() -> CrossShardMelee.hit(players.get(target), state)).join();
    }

    public static void finishMelee(long entity, byte[] result) {
        server.execute(() -> crossShardMelee.finish(players.get(entity), result));
    }

    public static byte[][] actorFrames() {
        return ShardActors.drain();
    }

    public static byte[] freezeActor(int id) {
        return server.submit(() -> ShardActors.freeze(id)).join();
    }

    public static void releaseActor(int id, boolean commit) {
        server.submit(() -> ShardActors.release(id, commit)).join();
    }

    public static boolean stageActor(String transfer, byte[] checkpoint) {
        var current = server;
        return current != null && current.submit(() -> ShardActors.stage(current.overworld(), transfer, checkpoint)).join();
    }

    public static int activateActor(String transfer) {
        return server.submit(() -> ShardActors.activate(transfer)).join();
    }

    public static void abortActor(String transfer) {
        server.submit(() -> ShardActors.abort(transfer)).join();
    }

    public static void tick() {
        ShardActors.sample();
        for (var entry : players.entrySet()) {
            ShardConnection connection = connections.get(entry.getKey());
            if (connection == null || isFrozen(entry.getValue())) {
                continue;
            }
            if (connection.packetListener instanceof TickablePacketListener tickable) {
                tickable.tick();
            }
            connection.recordPose(entry.getValue());
            if (ShardChunkIO.current() != null) {
                connection.recordDemand(entry.getValue());
            }
        }
    }

    private static String displayName(long clientId) {
        return String.format("AN%014x", clientId & 0x00ffffffffffffffL);
    }

    public static byte[] chat(long clientId, long entityId, byte[] bytes) {
        try {
            String text = ProtocolRegistry.CHAT_COMMAND.decode(bytes).text();
            if (text.isBlank() || text.length() > 256 || text.startsWith("/")
                    || text.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint) || codePoint == '§')) {
                return null;
            }
            return ProtocolRegistry.CHAT.encode(new ChatSignal(entityId, displayName(clientId), text));
        } catch (IOException invalid) {
            return null;
        }
    }

    public static byte[] region(long id, long generation, int dimension, double[] geometry) throws IOException {
        Shape shape = null;
        if (dimension != 0 && geometry.length <= 6 + 5 * 100) {
            var planes = new ArrayList<Plane>();
            for (int i = 6; i < geometry.length; i += 5) {
                planes.add(new Plane(new Point(geometry[i], geometry[i + 1], geometry[i + 2]),
                        geometry[i + 3], geometry[i + 4] != 0));
            }
            shape = new Shape(dimension, new Point(geometry[0], geometry[1], geometry[2]),
                    new Point(geometry[3], geometry[4], geometry[5]), planes);
        }
        return ProtocolRegistry.PLAYER_REGION.encode(new PlayerRegionSignal(id, generation, shape));
    }

    public static byte[] presence(long clientId, long entityId, boolean joined) throws IOException {
        return joined
                ? ProtocolRegistry.PLAYER_JOINED.encode(new PlayerJoinedSignal(entityId, displayName(clientId)))
                : ProtocolRegistry.PLAYER_LEFT.encode(new PlayerLeftSignal(entityId, displayName(clientId)));
    }

    public static byte[] shardMetrics(String shardId) throws IOException {
        return ProtocolRegistry.SHARD_METRICS.encode(new ShardMetricsSignal(UUID.fromString(shardId)));
    }

    public static void receivePacket(long entityId, byte[] bytes, int command) {
        DedicatedServer current = server;
        ShardConnection connection = connections.get(entityId);
        if (current != null && connection != null) {
            current.execute(() -> {
                var player = players.get(entityId);
                if (connections.get(entityId) == connection && player != null && !isFrozen(player)) {
                    connection.receive(bytes, command, entityId, player);
                }
            });
        }
    }

    public static long[] nextRemoteBreak() {
        return remoteTerrainBreaks.next();
    }

    public static int breakTerrainBlock(int x, int y, int z) {
        DedicatedServer current = server;
        if (current == null || !current.isRunning()) {
            return -1;
        }
        return current.submit(() -> RemoteTerrainBreaks.breakBlock(
                current.overworld(), new BlockPos(x, y, z))).join();
    }

    public static void completeRemoteBreak(long entity, int x, int y, int z, int sequence, int state) {
        DedicatedServer current = server;
        if (current != null && current.isRunning()) {
            current.execute(() -> remoteTerrainBreaks.complete(
                    entity, players.get(entity), new BlockPos(x, y, z), sequence, state));
        }
    }

    private record Pose(double x, double y, double z, float yaw, float pitch, float head, int flags) {}
    private record ChunkWindow(int x, int z, int view, int simulation) {}

    public static double[] takePose(long entityId) {
        ShardConnection connection = connections.get(entityId);
        var position = connection == null ? null : connection.transform.getAndSet(null);
        return position == null ? null : new double[] {position.x(), position.y(), position.z(),
                position.yaw(), position.pitch(), position.head(), position.flags()};
    }

    public static byte[] nextPacket(long entityId) {
        ShardConnection connection = connections.get(entityId);
        return connection == null ? null : connection.outbound.poll();
    }

    public static void sendTrackedTerrain(ServerPlayer viewer, ChunkPos chunk, Packet<?> packet) {
        if (((ServerConnectionAccessor) viewer.connection).atlasnetConnection() instanceof ShardConnection shard) {
            shard.sendTrackedTerrain(viewer.getId(), chunk, packet);
        } else {
            viewer.connection.send(packet);
        }
    }

    private static final class ShardConnection extends Connection {
        private static final SocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 0);

        private final ConcurrentLinkedQueue<byte[]> outbound = new ConcurrentLinkedQueue<>();
        private final AtomicReference<Pose> transform = new AtomicReference<>();
        private final AtomicReference<ChunkWindow> demand = new AtomicReference<>();
        private final Map<ChunkPos, ChunkDelivery> deliveredTerrain = new HashMap<>();
        private final ArrayDeque<List<ChunkDelivery>> pendingTerrainBatches = new ArrayDeque<>();
        private final List<ChunkDelivery> currentTerrainBatch = new ArrayList<>();
        private Pose lastPose;
        private ChunkWindow lastDemand;
        private final ProtocolInfo<?> play;
        private ProtocolInfo<?> inbound;
        private PacketListener packetListener;

        ShardConnection(DedicatedServer server) {
            super(PacketFlow.SERVERBOUND);
            play = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess()));
        }

        void recordPose(ServerPlayer player) {
            var pose = new Pose(player.getX(), player.getY(), player.getZ(), player.getYRot(),
                    player.getXRot(), player.getYHeadRot(), (player.isShiftKeyDown() ? 1 : 0)
                    | (player.isSprinting() ? 2 : 0) | (player.onGround() ? 4 : 0));
            if (!pose.equals(lastPose)) {
                lastPose = pose;
                transform.set(pose);
            }
        }

        void recordDemand(ServerPlayer player) {
            if (player.getChunkTrackingView() instanceof ChunkTrackingView.Positioned view) {
                var next = new ChunkWindow(view.center().x, view.center().z, view.viewDistance(),
                        player.server.getPlayerList().getSimulationDistance());
                if (!next.equals(lastDemand)) {
                    lastDemand = next;
                    demand.set(next);
                }
            }
        }

        void configure(DedicatedServer server) {
            var configuration = ConfigurationProtocols.CLIENTBOUND;
            encode(configuration, new ClientboundUpdateEnabledFeaturesPacket(
                    FeatureFlags.REGISTRY.toNames(server.getWorldData().enabledFeatures())));
            new SynchronizeRegistriesTask(List.of(), server.registries()).handleResponse(
                    List.of(), packet -> encode(configuration, packet));
            encode(configuration, ClientboundFinishConfigurationPacket.INSTANCE);
        }

        private void encode(ProtocolInfo protocol, Packet<?> packet) {
            enqueue(false, encodePacket(protocol, packet));
        }

        private void enqueue(boolean spatial, byte[] payload) {
            byte[] framed = new byte[payload.length + 1];
            framed[0] = (byte) (spatial ? 1 : 0);
            System.arraycopy(payload, 0, framed, 1, payload.length);
            outbound.add(framed);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private byte[] encodePacket(ProtocolInfo protocol, Packet<?> packet) {
            var buffer = Unpooled.buffer();
            try {
                protocol.codec().encode(buffer, packet);
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.readBytes(bytes);
                return bytes;
            } finally {
                buffer.release();
            }
        }

        private void sendTrackedTerrain(int viewer, ChunkPos chunk, Packet<?> packet) {
            var delivery = deliveredTerrain.get(chunk);
            byte[] data = encodePacket(play, packet);
            if (delivery != null && delivery.acknowledged
                    && data.length + TerrainUpdateSignal.HEADER_BYTES <= TerrainUpdateSignal.MAX_BYTES) {
                enqueue(true, new TerrainUpdateSignal(chunk.x, chunk.z, viewer, data).encode());
            } else {
                enqueue(false, data);
            }
        }

        private void recordTerrainDelivery(Packet<?> packet) {
            if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
                var delivery = new ChunkDelivery();
                deliveredTerrain.put(new ChunkPos(chunk.getX(), chunk.getZ()), delivery);
                currentTerrainBatch.add(delivery);
            } else if (packet instanceof ClientboundChunkBatchFinishedPacket) {
                pendingTerrainBatches.add(List.copyOf(currentTerrainBatch));
                currentTerrainBatch.clear();
            } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
                deliveredTerrain.remove(forget.pos());
            }
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return LOCAL;
        }

        @Override
        public String getLoggableAddress(boolean logIps) {
            return "AtlasNet";
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
            inbound = protocol;
            packetListener = listener;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void receive(byte[] bytes, int command, long entity, ServerPlayer player) {
            var buffer = Unpooled.wrappedBuffer(bytes);
            try {
                Packet packet = inbound.codec().decode(buffer);
                if (buffer.isReadable()) {
                    return;
                }
                boolean allowed = switch (command) {
                    case PlayerInputCommand.ID -> PlayerInputCommand.supports(packet);
                    case GameplayInputCommand.ID -> GameplayInputCommand.supports(packet);
                    case BlockInteractionCommand.ID -> ShardChunkIO.current() != null
                            && BlockInteractionCommand.supports(packet);
                    default -> packet instanceof ServerboundKeepAlivePacket
                            || packet instanceof ServerboundAcceptTeleportationPacket
                            || packet instanceof ServerboundChunkBatchReceivedPacket;
                };
                if (allowed) {
                    if (command == BlockInteractionCommand.ID && player != null
                            && packet instanceof ServerboundPlayerActionPacket action
                            && remoteTerrainBreaks.intercept(entity, player, action)) {
                        return;
                    }
                    if (command == BlockInteractionCommand.ID && player != null
                            && packet instanceof ServerboundUseItemOnPacket use) {
                        var hit = use.getHitResult();
                        var clicked = hit.getBlockPos();
                        if (!ShardChunkIO.ownsTerrain(ChunkPos.asLong(clicked))
                                || !ShardChunkIO.ownsTerrain(ChunkPos.asLong(clicked.relative(hit.getDirection())))) {
                            player.connection.ackBlockChangesUpTo(use.getSequence());
                            return;
                        }
                    }
                    if (packet instanceof ServerboundChunkBatchReceivedPacket && !pendingTerrainBatches.isEmpty()) {
                        pendingTerrainBatches.removeFirst().forEach(delivery -> delivery.acknowledged = true);
                    }
                    packet.handle(packetListener);
                }
            } catch (io.netty.handler.codec.DecoderException | IndexOutOfBoundsException invalid) {
                // Ignore malformed application input.
            } finally {
                buffer.release();
            }
        }

        @Override
        public void flushChannel() {
            // There is no Netty channel to flush.
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            if (packet instanceof ClientboundSystemChatPacket) {
                return;
            }
            if (packet instanceof BundlePacket<?> bundle) {
                bundle.subPackets().forEach(part -> encode(play, part));
            } else {
                if (ShardChunkIO.current() != null) {
                    recordTerrainDelivery(packet);
                }
                encode(play, packet);
            }
        }

        private static final class ChunkDelivery {
            private boolean acknowledged;
        }
    }
}
