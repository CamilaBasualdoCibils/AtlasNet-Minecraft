package net.atlasnet.minecraft.fabric.server;

import io.netty.buffer.Unpooled;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.atlasnet.minecraft.atlasnet.protocol.signals.TerrainSnapshotSignal;
import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.LoggerFactory;

/** Serializes owner snapshots using the same codec used by Minecraft's PlayerChunkSender. */
final class ChunkTerrainSnapshots {
    private static final int MAX_QUEUE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_CAPTURES = 8;
    private static final int HEADER_BYTES = Long.BYTES + 3 * Integer.BYTES;
    private final ConcurrentLinkedQueue<byte[]> pending = new ConcurrentLinkedQueue<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final PriorityQueue<Capture> waiting = new PriorityQueue<>(Comparator.comparingInt(Capture::distance));
    private int capturing;

    void capture(ChunkPos pos, long observer, int viewDistance, int distance, BooleanSupplier required) {
        waiting.add(new Capture(pos, observer, viewDistance, distance, required));
    }

    void tick(DedicatedServer server) {
        while (capturing < MAX_CAPTURES && queuedBytes.get() < MAX_QUEUE_BYTES / 2 && !waiting.isEmpty()) {
            var capture = waiting.remove();
            if (capture.required().getAsBoolean()) {
                startCapture(server, capture);
            }
        }
    }

    private void startCapture(DedicatedServer server, Capture capture) {
        var cache = server.overworld().getChunkSource();
        var pos = capture.pos();
        capturing++;
        // Calling the public chunk future from the server thread waits for generation.
        CompletableFuture.supplyAsync(() -> cache.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true))
                .thenCompose(future -> future)
                .whenComplete((result, error) -> server.execute(() -> {
                    capturing--;
                    if (error != null) {
                        LoggerFactory.getLogger(ChunkTerrainSnapshots.class).warn("Could not capture terrain {}", pos, error);
                    } else if (capture.required().getAsBoolean() && ShardChunkIO.ownsTerrain(pos.toLong())
                            && result.orElse(null) instanceof LevelChunk chunk) {
                        if (!enqueue(server, pos, capture.observer(), capture.viewDistance(),
                                new ClientboundLevelChunkWithLightPacket(chunk, cache.getLightEngine(), null, null))) {
                            waiting.add(capture);
                        }
                    }
                }));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    boolean enqueue(DedicatedServer server, ChunkPos pos, long observer, int viewDistance, Packet<?> packet) {
        var buffer = Unpooled.buffer();
        try {
            buffer.writeLong(observer).writeInt(pos.x).writeInt(pos.z).writeInt(viewDistance);
            var codec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                    RegistryFriendlyByteBuf.decorator(server.registryAccess())).codec();
            ((StreamCodec) codec).encode(new RegistryFriendlyByteBuf(buffer, server.registryAccess()), packet);
            int size = buffer.readableBytes();
            if (size > TerrainSnapshotSignal.MAX_PACKET + HEADER_BYTES) {
                LoggerFactory.getLogger(ChunkTerrainSnapshots.class)
                        .warn("AtlasNet packet exceeded the terrain packet size limit for {}", pos);
                return true;
            }
            if (queuedBytes.get() + size > MAX_QUEUE_BYTES) {
                return false;
            }
            byte[] payload = new byte[size];
            buffer.readBytes(payload);
            queuedBytes.addAndGet(size);
            pending.add(payload);
            return true;
        } finally {
            buffer.release();
        }
    }

    byte[] next() {
        byte[] packet = pending.poll();
        if (packet != null) {
            queuedBytes.addAndGet(-packet.length);
        }
        return packet;
    }

    private record Capture(ChunkPos pos, long observer, int viewDistance, int distance, BooleanSupplier required) {}
}
