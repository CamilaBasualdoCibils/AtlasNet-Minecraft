package net.atlasnet.minecraft.fabric.server;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Applies the AtlasNet-authorized portion of a player's view and simulation demand. */
final class ChunkDemandManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkDemandManager.class);
    private static final TicketType<Long> DEMAND = TicketType.create("atlasnet_player_demand", Long::compareUnsigned);
    private static final int VIEW_TICKET = 0;
    private static final int SIMULATION_TICKET = 2;

    private final Map<Long, Demand> observers = new HashMap<>();
    private final Map<ChunkPos, Map<Long, Integer>> remoteViewers = new HashMap<>();
    private final ChunkTerrainSnapshots snapshots = new ChunkTerrainSnapshots();

    void apply(DedicatedServer server, long observer, long revision, int x, int z,
               int viewDistance, int simulationDistance, boolean[] ownership, boolean remote, boolean refresh) {
        ServerChunkCache chunks = server.overworld().getChunkSource();
        var previous = observers.get(observer);
        if (previous != null && Long.compareUnsigned(revision, previous.revision()) <= 0) {
            return;
        }

        var tickets = new HashMap<ChunkPos, Integer>();
        var tracking = viewDistance == 0 ? ChunkTrackingView.EMPTY
                : ChunkTrackingView.of(new ChunkPos(x, z), viewDistance);
        if (viewDistance != 0) {
            int radius = Math.max(viewDistance + 1, simulationDistance);
            int index = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (!ownership[index++]) {
                        continue;
                    }
                    if (Math.max(Math.abs(dx), Math.abs(dz)) <= simulationDistance) {
                        tickets.put(new ChunkPos(x + dx, z + dz), SIMULATION_TICKET);
                    } else if (tracking.contains(x + dx, z + dz)) {
                        tickets.put(new ChunkPos(x + dx, z + dz), VIEW_TICKET);
                    }
                }
            }
        }

        var oldTickets = previous == null ? Map.<ChunkPos, Integer>of() : previous.tickets();
        tickets.forEach((pos, level) -> {
            if (!level.equals(oldTickets.get(pos))) {
                chunks.addRegionTicket(DEMAND, pos, level, observer);
            }
        });
        oldTickets.forEach((pos, level) -> {
            if (!level.equals(tickets.get(pos))) {
                chunks.removeRegionTicket(DEMAND, pos, level, observer);
            }
        });
        var next = new Demand(revision, tickets, tracking, remote);
        observers.put(observer, next);
        if (previous != null && previous.remote()) {
            for (var pos : previous.tickets().keySet()) {
                if (!remote || !next.canSee(pos)) {
                    var viewers = remoteViewers.get(pos);
                    if (viewers != null) {
                        viewers.remove(observer);
                        if (viewers.isEmpty()) {
                            remoteViewers.remove(pos);
                        }
                    }
                }
            }
        }
        if (remote) {
            for (var pos : tickets.keySet()) {
                if (tracking.contains(pos)) {
                    remoteViewers.computeIfAbsent(pos, key -> new HashMap<>()).put(observer, viewDistance);
                    if (refresh || previous == null || !previous.remote() || !previous.canSee(pos)) {
                        snapshots.capture(server, pos, observer, viewDistance, () -> {
                            var latest = observers.get(observer);
                            return latest.remote() && latest.canSee(pos);
                        });
                    }
                }
            }
        }
        if (previous == null || viewDistance == 0) {
            LOGGER.info("AtlasNet chunk demand for {} has {} owned tickets", Long.toUnsignedString(observer),
                    tickets.size());
        }
    }

    byte[] nextSnapshot() {
        return snapshots.next();
    }

    boolean remoteViewersPresent(ChunkPos pos) {
        return remoteViewers.containsKey(pos);
    }

    void sendRemoteTerrain(DedicatedServer server, ChunkPos pos, Packet<?> packet) {
        var viewers = remoteViewers.get(pos);
        if (viewers != null) {
            viewers.forEach((observer, view) -> snapshots.enqueue(server, pos, observer, view, packet));
        }
    }

    private record Demand(long revision, Map<ChunkPos, Integer> tickets, ChunkTrackingView tracking, boolean remote) {
        boolean canSee(ChunkPos pos) {
            return tickets.containsKey(pos) && tracking.contains(pos);
        }
    }
}
