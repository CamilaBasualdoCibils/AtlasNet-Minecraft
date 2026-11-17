package net.atlasnet.minecraft.fabric.client;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import net.atlasnet.minecraft.atlasnet.protocol.signals.TerrainSnapshotSignal;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

/** Buffers complete owner snapshots until the vanilla chunk tracking window catches up. */
final class TerrainSnapshotReceiver {
    private static final int MAX_IN_FLIGHT = 4;
    private static final int MAX_PENDING_BYTES = 16 * 1024 * 1024;

    private final LinkedHashMap<Transfer, Assembly> incoming = new LinkedHashMap<>();
    private final LinkedHashMap<ChunkPos, ArrayDeque<byte[]>> pending = new LinkedHashMap<>();
    private long viewer;
    private int pendingBytes;

    void setViewer(long viewer) {
        this.viewer = viewer;
    }

    void clear() {
        incoming.clear();
        pending.clear();
        pendingBytes = 0;
    }

    void accept(TerrainSnapshotSignal part) {
        if (part.viewer() != viewer) {
            return;
        }
        var pos = new ChunkPos(part.x(), part.z());
        var transfer = new Transfer(part.sequence(), pos);
        if (part.offset() == 0) {
            if (incoming.size() == MAX_IN_FLIGHT) {
                incoming.pollFirstEntry();
            }
            incoming.put(transfer, new Assembly(part.size()));
        }
        var assembly = incoming.get(transfer);
        if (assembly == null) {
            return;
        }
        if (part.offset() != assembly.offset || part.size() != assembly.bytes.length) {
            incoming.remove(transfer);
            return;
        }
        System.arraycopy(part.bytes(), 0, assembly.bytes, assembly.offset, part.bytes().length);
        assembly.offset += part.bytes().length;
        if (assembly.offset == assembly.bytes.length) {
            incoming.remove(transfer);
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
        incoming.keySet().removeIf(transfer -> transfer.pos().equals(pos));
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

    private record Transfer(long sequence, ChunkPos pos) {}

    private static final class Assembly {
        private final byte[] bytes;
        private int offset;

        Assembly(int size) {
            bytes = new byte[size];
        }
    }
}
