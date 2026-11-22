package net.atlasnet.minecraft.server;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Objects;
import net.atlasnet.minecraft.sdk.core.MinecraftIngressPackets;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.atlasnet.atlasnet.Shard;
import org.atlasnet.atlasnet.Signal;
import org.javagi.base.GErrorException;

/** Attaches the existing Minecraft server in the shard to AtlasNet's provider callbacks. */
public final class MinecraftShardBridge {
    private static final long MAX_QUEUED_BYTES = 16L * 1024 * 1024;
    private static final System.Logger LOG = System.getLogger(MinecraftShardBridge.class.getName());
    private final MinecraftServer server;
    private final Shard shard;
    // Connection map belongs to the Minecraft server thread.
    private final HashMap<Long, AtlasNetConnection> connections = new HashMap<>();
    private final ArrayDeque<Outbound> outgoing = new ArrayDeque<>();
    private long queuedBytes;

    private record Outbound(long client, long entity, byte[] packet) {}

    public MinecraftShardBridge(MinecraftServer server, Shard shard) {
        this.server = Objects.requireNonNull(server, "server");
        this.shard = Objects.requireNonNull(shard, "shard");
        shard.onClientSpawn((client, entity, placement, data) -> spawn(client, entity, data));
        shard.onCommand(command -> {
            long entity = command.readEntity();
            byte[] payload = command.getPayload();
            server.execute(() -> {
                var connection = connections.get(entity);
                if (connection != null) {
                    connection.receivePacket(payload);
                }
            });
        });
        shard.onUnregisterEntity(entity -> server.execute(() -> remove(entity)));
        shard.onPoll(this::flush);
        shard.onShutdown(() -> server.execute(() -> {
            for (var connection : connections.values()) {
                connection.closeFromAtlasNet(new DisconnectionDetails(Component.literal("Shard stopped")));
            }
            connections.clear();
        }));
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
        return true;
    }

    private void remove(long entity) {
        var connection = connections.remove(entity);
        if (connection != null) {
            connection.closeFromAtlasNet(new DisconnectionDetails(Component.literal("Disconnected")));
        }
    }

    /** Run once per Minecraft server tick so vanilla maintains its connection lifecycle. */
    public void tickConnections() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Minecraft connections require the server thread");
        }
        for (var connection : connections.values()) {
            connection.tick();
        }
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

    private void flush() {
        try {
            for (int i = 0; i < 512; i++) {
                var output = next();
                if (output == null) break;
                var signal = new Signal();
                signal.writeRecipient(output.client());
                signal.writeEntity(output.entity());
                signal.writeSignal(MinecraftIngressPackets.PLAY_PACKET_SIGNAL);
                signal.setPayload(output.packet());
                shard.dispatchRawSignals(new Signal[]{signal});
            }
            shard.flushSignals();
        } catch (GErrorException failure) {
            LOG.log(System.Logger.Level.ERROR, "Could not send Minecraft Play Signal", failure);
        }
    }
}
