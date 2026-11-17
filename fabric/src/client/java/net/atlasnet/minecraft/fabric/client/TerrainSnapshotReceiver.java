package net.atlasnet.minecraft.fabric.client;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import net.atlasnet.minecraft.atlasnet.protocol.signals.TerrainSnapshotSignal;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

/** Buffers complete owner snapshots until the vanilla chunk tracking window catches up. */
final class TerrainSnapshotReceiver {
    private static final int MAX_IN_FLIGHT_BYTES = 32 * 1024 * 1024;
    private static final int MAX_PENDING_BYTES = 16 * 1024 * 1024;

    private final LinkedHashMap<Transfer, Assembly> incoming = new LinkedHashMap<>();
    private final LinkedHashMap<ChunkPos, ArrayDeque<byte[]>> pending = new LinkedHashMap<>();
    private long viewer;
    private int pendingBytes;
    private int inFlightBytes;

    void setViewer(long viewer) {
        this.viewer = viewer;
    }

    void clear() {
        incoming.clear();
        pending.clear();
        pendingBytes = 0;
        inFlightBytes = 0;
    }

    void accept(TerrainSnapshotSignal part) {
        if (part.viewer() != viewer) {
            return;
        }
        var pos = new ChunkPos(part.x(), part.z());
        var transfer = new Transfer(part.sourceHigh(), part.sourceLow(), part.sequence(), pos);
        if (part.offset() == 0) {
            remove(transfer);
            while (inFlightBytes + part.size() > MAX_IN_FLIGHT_BYTES) {
                inFlightBytes -= incoming.pollFirstEntry().getValue().bytes.length;
            }
            incoming.put(transfer, new Assembly(part.size()));
            inFlightBytes += part.size();
        }
        var assembly = incoming.get(transfer);
        if (assembly == null) {
            return;
        }
        if (part.offset() != assembly.offset || part.size() != assembly.bytes.length) {
            remove(transfer);
            return;
        }
        System.arraycopy(part.bytes(), 0, assembly.bytes, assembly.offset, part.bytes().length);
        assembly.offset += part.bytes().length;
        if (assembly.offset == assembly.bytes.length) {
            remove(transfer);
            pending.computeIfAbsent(pos, ignored -> new ArrayDeque<>()).add(assembly.bytes);
            pendingBytes += assembly.bytes.length;
            while (pendingBytes > MAX_PENDING_BYTES) {
                discard(pending.pollFirstEntry().getValue());
            }
        }
    }

    void apply(ClientLevel level, Consumer<byte[]> receive) {
        if (level == null || pending.isEmpty()) {
            return;
        }
        var entries = pending.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (level.hasChunk(entry.getKey().x, entry.getKey().z)) {
                entries.remove();
                for (byte[] packet : entry.getValue()) {
                    pendingBytes -= packet.length;
                    receive.accept(packet);
                }
            }
        }
    }

    void forget(ChunkPos pos) {
        incoming.entrySet().removeIf(entry -> {
            if (!entry.getKey().pos().equals(pos)) {
                return false;
            }
            inFlightBytes -= entry.getValue().bytes.length;
            return true;
        });
        var packets = pending.remove(pos);
        if (packets != null) {
            discard(packets);
        }
    }

    private void discard(ArrayDeque<byte[]> packets) {
        for (byte[] packet : packets) {
            pendingBytes -= packet.length;
        }
    }

    private void remove(Transfer transfer) {
        var removed = incoming.remove(transfer);
        if (removed != null) {
            inFlightBytes -= removed.bytes.length;
        }
    }

    private record Transfer(long sourceHigh, long sourceLow, long sequence, ChunkPos pos) {}

    private static final class Assembly {
        private final byte[] bytes;
        private int offset;

        Assembly(int size) {
            bytes = new byte[size];
        }
    }
}
