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
    private final MinecraftServer server;
    private final Shard shard;
    // Connection map belongs to the Minecraft server thread.
    private final HashMap<Long, AtlasNetConnection> connections = new HashMap<>();
    private final HashSet<Long> confirmedPlayers = new HashSet<>();
    private final HashSet<Long> frozenPlayers = new HashSet<>();
    private final HashMap<Long, Long> clients = new HashMap<>();
    private final HashMap<Long, StagedPlayer> stagedPlayers = new HashMap<>();
    private final ConcurrentHashMap<Long, Position> transforms = new ConcurrentHashMap<>();
    private final BiConsumer<Long, ServerPlayer> playerSnapshot;
    private final java.util.function.LongConsumer playerRetired;
    private int ticks;
    private final ArrayDeque<Outbound> outgoing = new ArrayDeque<>();
    private long queuedBytes;

    public record PlayerTransfer(long client, int playId, GameProfile profile, ClientInformation information,
                                 InetSocketAddress peer) {}
    private record Position(double x, double y, double z) {}
    private record StagedPlayer(PlayerTransfer player, CompoundTag state) {}

    private record Outbound(long client, long entity, byte[] packet) {}

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
        shard.onClientSpawn((client, entity, placement, data) -> spawn(client, entity, data));
        shard.onCommand(command -> {
            long entity = command.readEntity();
            byte[] payload = command.getPayload();
            server.execute(() -> {
                if (frozenPlayers.contains(entity)) return;
                var staged = stagedPlayers.remove(entity);
                if (staged != null) startTransferredPlayer(entity, staged);
                var connection = connections.get(entity);
                if (connection != null) {
                    // Routed Play commands arrive only after AtlasNet publishes ownership.
                    confirmedPlayers.add(entity);
                    connection.receivePacket(payload);
                }
            });
        });
        shard.onUnregisterEntity(entity -> server.execute(() -> remove(entity)));
        shard.onPoll(this::flush);

    }

    private boolean spawn(long client, long entity, byte[] data) {
        try {
            if (server.isSameThread()) {
                return place(client, entity, data);
            }
            return server.submit(() -> place(client, entity, data)).join();
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not initialize the authenticated Minecraft player", failure);
            return false;
        }
    }

    private boolean place(long client, long entity, byte[] data) {
        if (connections.containsKey(entity)) {
            return false;
        }
        var connection = MinecraftPlayerJoin.place(server, data,
                packet -> enqueue(new Outbound(client, entity, packet)));
        connections.put(entity, connection);
        clients.put(entity, client);
        return true;
    }

    private void remove(long entity) {
        transforms.remove(entity);
        clients.remove(entity);
        frozenPlayers.remove(entity);
        stagedPlayers.remove(entity);
        var connection = connections.remove(entity);
        if (connection != null) {
            if (confirmedPlayers.remove(entity)) {
                if (connection.getPacketListener() instanceof ServerGamePacketListenerImpl play) {
                    playerSnapshot.accept(entity, play.player);
                }
                playerRetired.accept(entity);
            }
            connection.closeFromAtlasNet(new DisconnectionDetails(Component.literal("Disconnected")));
        }
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
            if (capture && confirmedPlayers.contains(entry.getKey()) && connection.isConnected()
                    && connection.getPacketListener() instanceof ServerGamePacketListenerImpl play) {
                playerSnapshot.accept(entry.getKey(), play.player);
                transforms.put(entry.getKey(), new Position(play.player.getX(), play.player.getY(), play.player.getZ()));
            }
        }
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
        if (player == null || player.isPassenger() || player.isVehicle()) return null;
        var connection = connections.get(entity);
        if (!(connection.getRemoteAddress() instanceof InetSocketAddress peer)) return null;
        var client = clients.get(entity);
        if (client == null) return null;
        frozenPlayers.add(entity);
        transforms.remove(entity);
        return new PlayerTransfer(client, player.getId(), player.getGameProfile(), player.clientInformation(), peer);
    }

    public void resumePlayer(long entity) {
        frozenPlayers.remove(entity);
    }

    /** Dispose the source-side embedded channel after commit; the endpoint keeps the TCP session. */
    public void detachPlayer(long entity) {
        confirmedPlayers.remove(entity);
        remove(entity);
    }

    public boolean canRestorePlayer(long entity, PlayerTransfer transfer) {
        return !connections.containsKey(entity) && !stagedPlayers.containsKey(entity) &&
                server.getPlayerList().getPlayer(transfer.profile().getId()) == null &&
                server.getPlayerList().getPlayers().stream().noneMatch(player -> player.getId() == transfer.playId()) &&
                server.getPlayerList().canPlayerLogin(transfer.peer(), transfer.profile()) == null;
    }

    public boolean stagePlayer(long entity, PlayerTransfer transfer, CompoundTag state) {
        if (!canRestorePlayer(entity, transfer)) return false;
        stagedPlayers.put(entity, new StagedPlayer(transfer, state));
        return true;
    }

    private void startTransferredPlayer(long entity, StagedPlayer staged) {
        long client = staged.player().client();
        var player = staged.player();
        var connection = MinecraftPlayerJoin.resumePlay(server, player.profile(), player.information(),
                player.peer(), player.playId(), staged.state(), packet -> enqueue(new Outbound(client, entity, packet)));
        connections.put(entity, connection);
        clients.put(entity, client);
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
                dispatchPlayPacket(shard, output.client(), output.entity(), output.packet());
            }
            shard.flushSignals();
            for (var sampled : transforms.entrySet()) {
                long entity = sampled.getKey();
                var position = sampled.getValue();
                if (!transforms.remove(entity, position)) continue;
                var transform = new Transform();
                transform.writeX(position.x());
                transform.writeY(position.y());
                transform.writeZ(position.z());
                shard.updateEntityTransform(entity, transform);
            }
        } catch (GErrorException failure) {
            LOG.log(System.Logger.Level.ERROR, "Could not send Minecraft Play Signal", failure);
        }
    }
}
