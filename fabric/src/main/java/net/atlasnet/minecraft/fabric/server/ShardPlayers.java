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
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
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
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
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
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;

public final class ShardPlayers {
    private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.parse(AtlasNetWorlds.OVERWORLD_DIMENSION));
    private static volatile DedicatedServer server;
    private static final Map<Long, ServerPlayer> players = new HashMap<>();
    private static final Set<ServerPlayer> frozen = new HashSet<>();
    private static final Set<Long> disconnectingAfterUse = new HashSet<>();
    private static final Map<Long, StagedPlayer> staged = new HashMap<>();
    private static final int MAX_PLAYER_NBT = 2 * 1024 * 1024;
    private static ServerPlayer loadingTransfer;
    private static CompoundTag transferTag;
    private static final Map<Long, ShardConnection> connections = new ConcurrentHashMap<>();
    private static final ChunkDemandManager chunkDemand = new ChunkDemandManager();
    private static final RemoteTerrainBreaks remoteTerrainBreaks = new RemoteTerrainBreaks();
    private static final RemoteTerrainUses remoteTerrainUses = new RemoteTerrainUses();
    private static final TerrainCollisionReplicas collisionReplicas = new TerrainCollisionReplicas();
    private static final CrossShardMelee crossShardMelee = new CrossShardMelee();

    private ShardPlayers() {
    }

    public static int trackingRange() {
        // Keep entity interest bounded independently of the terrain streaming radius.
        return Math.min(5, server.getPlayerList().getViewDistance()) * 16;
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

    public static void recordCollisionChanges(ChunkPos pos, Packet<?> packet) {
        collisionReplicas.capture(pos, packet);
    }

    public static void collisionReplicaLoaded(LevelChunk chunk) {
        collisionReplicas.chunkLoaded(chunk);
    }

    public static void collisionReplicaUnloaded(ChunkPos pos) {
        collisionReplicas.chunkUnloaded(pos);
    }

    public static byte[] nextCollisionChanges() {
        return collisionReplicas.next();
    }

    public static void applyCollisionChanges(byte[] changes) {
        var current = server;
        if (current != null && current.isRunning()) {
            current.execute(() -> collisionReplicas.receive(current.overworld(), changes));
        }
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
            var level = current.overworld();
            var player = new ServerPlayer(current, level, profile, cookie.clientInformation());
            player.moveTo(x, y, z, 0, 0);
            if (ShardChunkIO.current() != null && !level.noCollision(player)) {
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, player.getBlockX(), player.getBlockZ());
                if (surface > y) {
                    player.moveTo(x, surface, z, 0, 0);
                }
            }
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
            var connection = connections.get(entityId);
            if (player == null || !player.isAlive() || player.isPassenger() || player.isVehicle()
                    || player.isSleeping() || connection == null || connection.hasPendingTeleport()
                    || remoteTerrainUses.pending(entityId)) {
                return null;
            }
            try {
                var data = player.saveWithoutId(new CompoundTag());
                data.putBoolean("AtlasNetSprinting", player.isSprinting());
                data.putBoolean("AtlasNetCrouching", player.isShiftKeyDown());
                data.putInt("AtlasNetMainSwing", connection.mainSwing);
                data.putInt("AtlasNetOffSwing", connection.offSwing);
                data.putLong("AtlasNetMovementSequence", connection.lastMovementSequence);
                var preferences = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    player.clientInformation().write(preferences);
                    byte[] bytes = new byte[preferences.readableBytes()];
                    preferences.readBytes(bytes);
                    data.putByteArray("AtlasNetClientInformation", bytes);
                } finally {
                    preferences.release();
                }
                remoteTerrainBreaks.save(entityId, player, data);
                var bytes = new ByteArrayOutputStream();
                NbtIo.writeCompressed(data, bytes);
                if (bytes.size() > maxBytes) {
                    return null;
                }
                connection.recordPose(player);
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
                var preferences = new FriendlyByteBuf(Unpooled.wrappedBuffer(
                        tag.getByteArray("AtlasNetClientInformation")));
                CommonListenerCookie cookie;
                try {
                    cookie = new CommonListenerCookie(profile, 0, new ClientInformation(preferences), false);
                } finally {
                    preferences.release();
                }
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
            connection.handoffLogin = true;
            connection.mainSwing = incoming.tag().getInt("AtlasNetMainSwing");
            connection.offSwing = incoming.tag().getInt("AtlasNetOffSwing");
            connection.lastMovementSequence = incoming.tag().getLong("AtlasNetMovementSequence");
            loadingTransfer = incoming.player();
            transferTag = incoming.tag();
            try {
                server.getPlayerList().placeNewPlayer(connection, incoming.player(), incoming.cookie());
            } finally {
                loadingTransfer = null;
                transferTag = null;
            }
            incoming.player().setSprinting(incoming.tag().getBoolean("AtlasNetSprinting"));
            incoming.player().setShiftKeyDown(incoming.tag().getBoolean("AtlasNetCrouching"));
            remoteTerrainBreaks.restore(entityId, incoming.player(), incoming.tag());
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

    public static boolean ownsConnection(Connection connection) {
        return connection instanceof ShardConnection;
    }

    public static boolean inventoryReserved(ServerPlayer player) {
        return remoteTerrainUses.inventoryReserved(player);
    }

    public static boolean deferEquipmentDrop(ServerPlayer player) {
        return remoteTerrainUses.deferEquipmentDrop(player);
    }

    public interface EquipmentDropAccess {
        void atlasnet$dropEquipment();
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
            remoteTerrainBreaks.discard(entityId);
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
            if (remoteTerrainUses.pending(entityId)) {
                disconnectingAfterUse.add(entityId);
                frozen.add(players.get(entityId));
            } else {
                removeDisconnectedPlayer(current, entityId);
            }
        });
    }

    private static void removeDisconnectedPlayer(DedicatedServer current, long entity) {
        var player = players.remove(entity);
        if (player != null) {
            frozen.remove(player);
            crossShardMelee.discard(player);
            remoteTerrainBreaks.discard(entity);
            current.getPlayerList().remove(player);
        }
    }

    public static byte[] prepareMelee(long entity) {
        return server.submit(() -> remoteTerrainUses.pending(entity) ? null
                : crossShardMelee.prepare(players.get(entity))).join();
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
        chunkDemand.tick(server);
        remoteTerrainBreaks.tick(players, remoteTerrainUses);
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

    public static void receivePacket(long entityId, byte[] bytes, int command, boolean previousEpoch) {
        DedicatedServer current = server;
        ShardConnection connection = connections.get(entityId);
        if (current != null && connection != null) {
            current.execute(() -> {
                var player = players.get(entityId);
                if (connections.get(entityId) == connection && player != null && !isFrozen(player)) {
                    if (previousEpoch) {
                        connection.queueHandoffInput(bytes, command);
                    } else {
                        connection.receive(bytes, command, entityId, player);
                    }
                }
            });
        }
    }

    public static byte[] nextRemoteTerrainUse() {
        return remoteTerrainUses.next();
    }

    public static byte[] applyRemoteTerrainUse(long target, byte[] request) {
        var current = server;
        if (current == null || !current.isRunning()) {
            return null;
        }
        return current.submit(() -> RemoteTerrainUses.apply(current.overworld(), BlockPos.of(target), request)).join();
    }

    public static void completeRemoteTerrainUse(long entity, byte[] result) {
        var current = server;
        if (current != null && current.isRunning()) {
            current.execute(() -> {
                remoteTerrainUses.complete(entity, result, remoteTerrainBreaks);
                if (!remoteTerrainUses.pending(entity) && disconnectingAfterUse.remove(entity)) {
                    removeDisconnectedPlayer(current, entity);
                    return;
                }
                var connection = connections.get(entity);
                if (connection != null) {
                    connection.resumeInteractions(entity);
                }
            });
        }
    }

    private record Pose(double x, double y, double z, float yaw, float pitch, float head, int flags,
                        int mainSwing, int offSwing, int mainItem, int offItem, int headItem, int chestItem,
                        int legsItem, int feetItem) {}
    private record ChunkWindow(int x, int z, int view, int simulation) {}

    public static double[] takePose(long entityId) {
        ShardConnection connection = connections.get(entityId);
        var position = connection == null ? null : connection.transform.getAndSet(null);
        return position == null ? null : new double[] {position.x(), position.y(), position.z(),
                position.yaw(), position.pitch(), position.head(), position.flags(),
                position.mainSwing(), position.offSwing(), position.mainItem(), position.offItem(),
                position.headItem(), position.chestItem(), position.legsItem(), position.feetItem()};
    }

    public static byte[] nextPacket(long entityId) {
        ShardConnection connection = connections.get(entityId);
        return connection == null ? null : connection.outbound.poll();
    }

    public static byte[] nextPlayTerrainPacket(long entityId) {
        ShardConnection connection = connections.get(entityId);
        return connection == null || !connection.playAcknowledged ? null : connection.terrain.poll();
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
        private final ConcurrentLinkedQueue<byte[]> terrain = new ConcurrentLinkedQueue<>();
        private final AtomicReference<Pose> transform = new AtomicReference<>();
        private final AtomicReference<ChunkWindow> demand = new AtomicReference<>();
        private final Map<ChunkPos, ChunkDelivery> deliveredTerrain = new HashMap<>();
        private final ArrayDeque<List<ChunkDelivery>> pendingTerrainBatches = new ArrayDeque<>();
        private final List<ChunkDelivery> currentTerrainBatch = new ArrayList<>();
        private final ArrayDeque<DeferredInteraction> deferredInteractions = new ArrayDeque<>();
        private final ArrayDeque<DeferredInteraction> handoffInputs = new ArrayDeque<>();
        private boolean handoffLogin;
        private volatile boolean playAcknowledged;
        private int mainSwing;
        private int offSwing;
        private long lastMovementSequence;
        private int handoffTeleport = -1;
        private boolean awaitingRespawnPosition;
        private int respawnTeleport = -1;
        private int pendingTeleport = -1;
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
            if (hasPendingTeleport()) {
                return;
            }
            var pose = new Pose(player.getX(), player.getY(), player.getZ(), player.getYRot(),
                    player.getXRot(), player.getYHeadRot(), (player.isShiftKeyDown() ? 1 : 0)
                    | (player.isSprinting() ? 2 : 0) | (player.onGround() ? 4 : 0)
                    | (player.getAbilities().flying ? 8 : 0) | (player.isUsingItem() ? 16 : 0)
                    | (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.OFF_HAND ? 32 : 0)
                    | (player.isSwimming() ? 64 : 0) | (player.isFallFlying() ? 128 : 0),
                    mainSwing, offSwing, itemId(player, EquipmentSlot.MAINHAND), itemId(player, EquipmentSlot.OFFHAND),
                    itemId(player, EquipmentSlot.HEAD), itemId(player, EquipmentSlot.CHEST),
                    itemId(player, EquipmentSlot.LEGS), itemId(player, EquipmentSlot.FEET));
            if (!pose.equals(lastPose)) {
                lastPose = pose;
                transform.set(pose);
            }
        }

        private static int itemId(ServerPlayer player, EquipmentSlot slot) {
            return BuiltInRegistries.ITEM.getId(player.getItemBySlot(slot).getItem());
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
            enqueue(outbound, spatial, payload);
        }

        private static void enqueue(ConcurrentLinkedQueue<byte[]> queue, boolean spatial, byte[] payload) {
            byte[] framed = new byte[payload.length + 1];
            framed[0] = (byte) (spatial ? 1 : 0);
            System.arraycopy(payload, 0, framed, 1, payload.length);
            queue.add(framed);
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
                enqueue(terrain, false, data);
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
            if ((command == PlayerInputCommand.ID || command == BlockInteractionCommand.ID)
                    && queueHandoffInput(bytes, command)) {
                return;
            }
            if ((command == BlockInteractionCommand.ID || command == GameplayInputCommand.ID)
                    && remoteTerrainUses.pending(entity)) {
                if (deferredInteractions.size() < 128) {
                    deferredInteractions.addLast(new DeferredInteraction(bytes, command));
                }
                return;
            }
            var buffer = Unpooled.wrappedBuffer(bytes);
            try {
                long sequence = command == PlayerInputCommand.ID ? buffer.readLong() : 0;
                Packet packet = inbound.codec().decode(buffer);
                if (buffer.isReadable()) {
                    return;
                }
                boolean allowed = switch (command) {
                    case PlayerInputCommand.ID -> PlayerInputCommand.supports(packet);
                    case GameplayInputCommand.ID -> GameplayInputCommand.supports(packet);
                    case BlockInteractionCommand.ID -> ShardChunkIO.current() != null
                            && BlockInteractionCommand.supports(packet);
                    default -> packet instanceof ServerboundAcceptTeleportationPacket
                            || packet instanceof ServerboundChunkBatchReceivedPacket;
                };
                if (allowed) {
                    if (packet instanceof ServerboundMovePlayerPacket) {
                        if (sequence <= lastMovementSequence) {
                            return;
                        }
                        lastMovementSequence = sequence;
                    }
                    if (command == GameplayInputCommand.ID && packet instanceof ServerboundContainerClickPacket click
                            && remoteTerrainUses.chestClick(entity, player, click)) {
                        return;
                    }
                    if (command == BlockInteractionCommand.ID && player != null
                            && packet instanceof ServerboundPlayerActionPacket action
                            && remoteTerrainBreaks.intercept(entity, player, action, remoteTerrainUses)) {
                        return;
                    }
                    if (command == BlockInteractionCommand.ID && player != null
                            && packet instanceof ServerboundUseItemOnPacket use
                            && remoteTerrainUses.intercept(entity, player, use)) {
                        return;
                    }
                    if (packet instanceof ServerboundChunkBatchReceivedPacket && !pendingTerrainBatches.isEmpty()) {
                        pendingTerrainBatches.removeFirst().forEach(delivery -> delivery.acknowledged = true);
                    }
                    packet.handle(packetListener);
                    if (packet instanceof ServerboundSwingPacket swing) {
                        if (swing.getHand() == InteractionHand.MAIN_HAND) {
                            ++mainSwing;
                        } else {
                            ++offSwing;
                        }
                    }
                    if (packetListener instanceof ServerGamePacketListenerImpl game && player != game.player
                            && players.replace(entity, player, game.player)) {
                        // Vanilla respawn replaces ServerPlayer while retaining its listener and entity ID.
                        remoteTerrainBreaks.discard(entity);
                        crossShardMelee.discard(player);
                        player = game.player;
                    }
                    if (packet instanceof ServerboundAcceptTeleportationPacket ack) {
                        playAcknowledged = true;
                        if (respawnTeleport == ack.getId()) {
                            respawnTeleport = -1;
                            recordPose(player);
                        }
                        if (pendingTeleport == ack.getId()) {
                            pendingTeleport = -1;
                            recordPose(player);
                        }
                        if (handoffTeleport == ack.getId()) {
                            handoffTeleport = -1;
                            while (!handoffInputs.isEmpty()) {
                                var input = handoffInputs.removeFirst();
                                receive(input.bytes(), input.id(), entity, player);
                            }
                        }
                    }
                }
            } catch (io.netty.handler.codec.DecoderException | IndexOutOfBoundsException invalid) {
                // Ignore malformed application input.
            } finally {
                buffer.release();
            }
        }

        boolean respawnPending() {
            return awaitingRespawnPosition || respawnTeleport >= 0;
        }

        boolean hasPendingTeleport() {
            return respawnPending() || pendingTeleport >= 0;
        }

        boolean queueHandoffInput(byte[] bytes, int command) {
            if (handoffTeleport < 0) {
                return false;
            }
            if (handoffInputs.size() < 128) {
                handoffInputs.addLast(new DeferredInteraction(bytes, command));
            }
            return true;
        }

        void resumeInteractions(long entity) {
            while (!deferredInteractions.isEmpty() && !remoteTerrainUses.pending(entity)) {
                var player = players.get(entity);
                if (player == null) {
                    return;
                }
                var command = deferredInteractions.removeFirst();
                receive(command.bytes(), command.id(), entity, player);
            }
        }

        private record DeferredInteraction(byte[] bytes, int id) {}

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
                if (packet instanceof ClientboundRespawnPacket) {
                    playAcknowledged = false;
                    pendingTeleport = -1;
                    awaitingRespawnPosition = true;
                } else if (packet instanceof ClientboundPlayerPositionPacket position) {
                    if (handoffLogin) {
                        handoffLogin = false;
                        handoffTeleport = position.getId();
                    }
                    if (respawnPending()) {
                        awaitingRespawnPosition = false;
                        respawnTeleport = position.getId();
                    } else if (playAcknowledged) {
                        pendingTeleport = position.getId();
                    }
                }
                if (ShardChunkIO.current() != null) {
                    recordTerrainDelivery(packet);
                }
                if (packet instanceof ClientboundLevelChunkWithLightPacket
                        || packet instanceof ClientboundChunkBatchStartPacket
                        || packet instanceof ClientboundChunkBatchFinishedPacket
                        || packet instanceof ClientboundForgetLevelChunkPacket
                        || packet instanceof ClientboundLightUpdatePacket
                        || packet instanceof ClientboundSetChunkCacheCenterPacket
                        || packet instanceof ClientboundSetChunkCacheRadiusPacket) {
                    enqueue(terrain, false, encodePacket(play, packet));
                } else {
                    encode(play, packet);
                }
            }
        }

        private static final class ChunkDelivery {
            private boolean acknowledged;
        }
    }
}
