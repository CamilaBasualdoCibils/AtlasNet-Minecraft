package net.atlasnet.minecraft.fabric.server;

import io.netty.buffer.Unpooled;
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
    private static final int HEADER_BYTES = Long.BYTES + 3 * Integer.BYTES;
    private final ConcurrentLinkedQueue<byte[]> pending = new ConcurrentLinkedQueue<>();
    private final AtomicLong queuedBytes = new AtomicLong();

    void capture(DedicatedServer server, ChunkPos pos, long observer, int viewDistance, BooleanSupplier required) {
        var cache = server.overworld().getChunkSource();
        // Calling the public chunk future from the server thread waits for generation.
        CompletableFuture.supplyAsync(() -> cache.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true))
                .thenCompose(future -> future)
                .thenAccept(result -> server.execute(() -> {
                    if (required.getAsBoolean() && ShardChunkIO.ownsTerrain(pos.toLong())
                            && result.orElse(null) instanceof LevelChunk chunk) {
                        enqueue(server, pos, observer, viewDistance, new ClientboundLevelChunkWithLightPacket(
                                chunk, cache.getLightEngine(), null, null));
                    }
                }))
                .exceptionally(error -> {
                    LoggerFactory.getLogger(ChunkTerrainSnapshots.class).warn("Could not capture terrain {}", pos, error);
                    return null;
                });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    void enqueue(DedicatedServer server, ChunkPos pos, long observer, int viewDistance, Packet<?> packet) {
        var buffer = Unpooled.buffer();
        try {
            buffer.writeLong(observer).writeInt(pos.x).writeInt(pos.z).writeInt(viewDistance);
            var codec = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                    RegistryFriendlyByteBuf.decorator(server.registryAccess())).codec();
            ((StreamCodec) codec).encode(new RegistryFriendlyByteBuf(buffer, server.registryAccess()), packet);
            int size = buffer.readableBytes();
            if (size > TerrainSnapshotSignal.MAX_PACKET + HEADER_BYTES
                    || queuedBytes.get() + size > MAX_QUEUE_BYTES) {
                LoggerFactory.getLogger(ChunkTerrainSnapshots.class)
                        .warn("AtlasNet packet exceeded terrain send budget for {}", pos);
                return;
            }
            byte[] payload = new byte[size];
            buffer.readBytes(payload);
            queuedBytes.addAndGet(size);
            pending.add(payload);
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
}
