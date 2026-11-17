package net.atlasnet.minecraft.atlasnet.protocol;

import net.atlasnet.minecraft.atlasnet.protocol.commands.ChatCommand;
import net.atlasnet.minecraft.atlasnet.protocol.signals.ChatSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerJoinedSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerLeftSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerRegionSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerRegionSignal.Plane;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerRegionSignal.Point;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerRegionSignal.Shape;
import net.atlasnet.minecraft.atlasnet.protocol.signals.TerrainSnapshotSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.TerrainUpdateSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.ShardMetricsSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.WorldActorSignal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ProtocolRegistryTest {
    public static void main(String[] args) throws IOException {
        Set.of(ProtocolRegistry.CHAT_COMMAND.id(), ProtocolRegistry.CHAT.id(),
                ProtocolRegistry.PLAYER_JOINED.id(), ProtocolRegistry.PLAYER_LEFT.id(),
                ProtocolRegistry.PLAYER_REGION.id(), TerrainUpdateSignal.ID, TerrainSnapshotSignal.VIEWER_ID,
                TerrainSnapshotSignal.ID, ProtocolRegistry.SHARD_METRICS.id(),
                WorldActorSignal.SHOW, WorldActorSignal.UPDATE);

        long unsignedHighBit = Long.MIN_VALUE + 42;
        roundTrip(ProtocolRegistry.CHAT_COMMAND, new ChatCommand("Hello 🌍"));
        roundTrip(ProtocolRegistry.CHAT, new ChatSignal(unsignedHighBit, "Player", "Hello"));
        roundTrip(ProtocolRegistry.PLAYER_JOINED, new PlayerJoinedSignal(unsignedHighBit, "Player"));
        roundTrip(ProtocolRegistry.PLAYER_LEFT, new PlayerLeftSignal(unsignedHighBit, "Player"));
        roundTrip(ProtocolRegistry.PLAYER_REGION, new PlayerRegionSignal(67, unsignedHighBit, null));
        roundTrip(ProtocolRegistry.SHARD_METRICS, new ShardMetricsSignal(new UUID(unsignedHighBit, 7)));

        var unbounded = new Shape(2, new Point(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, -100),
                new Point(100, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
                List.of(new Plane(new Point(1, 0, 2), 10, true)));
        roundTrip(ProtocolRegistry.PLAYER_REGION, new PlayerRegionSignal(67, unsignedHighBit, unbounded));

        var finite = new Shape(2, new Point(0, Double.NEGATIVE_INFINITY, 0),
                new Point(100, Double.POSITIVE_INFINITY, 100), List.of());
        roundTrip(ProtocolRegistry.PLAYER_REGION, new PlayerRegionSignal(68, 1, finite));

        expectBadPayload(() -> ProtocolRegistry.CHAT_COMMAND.encode(new ChatCommand("x".repeat(1025))));
        expectBadPayload(() -> ProtocolRegistry.CHAT_COMMAND.decode(new byte[] {0, 1, (byte) 0xff}));
        expectBadPayload(() -> ProtocolRegistry.CHAT_COMMAND.decode(new byte[] {0, 0, 1}));
        terrainUpdate();
        terrainSnapshot();
        worldActor();
    }

    private static void worldActor() {
        var frame = ByteBuffer.allocate(2 * Long.BYTES + WorldActorSignal.STATE_BYTES)
                .putLong(16).putLong(42).putDouble(-34.5).putDouble(72).putDouble(91.25)
                .putFloat(30).putFloat(-12).putLong(9).putLong(7).put(WorldActorSignal.ARROW);
        var pose = WorldActorSignal.decode(frame.array());
        if (pose.entity() != 16 || pose.revision() != 42 || !pose.uuid().equals(new UUID(9, 7))
                || pose.x() != -34.5 || pose.y() != 72 || pose.z() != 91.25
                || pose.yaw() != 30 || pose.pitch() != -12 || pose.kind() != WorldActorSignal.ARROW) {
            throw new AssertionError("World actor pose differs from the shard frame layout");
        }
        frame.put(frame.capacity() - 1, WorldActorSignal.ITEM);
        try {
            WorldActorSignal.decode(frame.array());
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected an item actor with missing item data to be rejected");
    }

    private static void terrainSnapshot() {
        long viewer = Long.MIN_VALUE + 10;
        var frame = ByteBuffer.allocate(35)
                .putLong(viewer).putLong(3).putInt(-16).putInt(2).putInt(4096).putInt(4099)
                .put(new byte[] {1, 2, 3});
        var part = TerrainSnapshotSignal.decode(frame.array());
        if (part.viewer() != viewer || part.sequence() != 3 || part.x() != -16 || part.z() != 2
                || part.offset() != 4096 || part.size() != 4099
                || !Arrays.equals(part.bytes(), new byte[] {1, 2, 3})) {
            throw new AssertionError("Authoritative terrain fragment does not match the shard wire layout");
        }
        frame.putInt(24, Integer.MAX_VALUE);
        try {
            TerrainSnapshotSignal.decode(frame.array());
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected a fragment that extends outside its packet to be rejected");
    }

    private static void terrainUpdate() {
        var record = new TerrainUpdateSignal(-15, 0x01020304, 21, new byte[] {7, 4, 0});
        byte[] bytes = record.encode();
        if (!Arrays.equals(bytes, new byte[] {
                -1, -1, -1, -15, 1, 2, 3, 4, 0, 0, 0, 21, 7, 4, 0})) {
            throw new AssertionError("Terrain coordinates must match the native big-endian format");
        }
        var decoded = TerrainUpdateSignal.decode(bytes);
        if (decoded.chunkX() != record.chunkX() || decoded.chunkZ() != record.chunkZ()
                || decoded.viewer() != record.viewer() || !Arrays.equals(decoded.packet(), record.packet())) {
            throw new AssertionError("Terrain update changed during encoding");
        }
        try {
            TerrainUpdateSignal.decode(Arrays.copyOf(bytes, TerrainUpdateSignal.HEADER_BYTES));
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected terrain frames with no Minecraft packet to be rejected");
    }

    private static <T> void roundTrip(ProtocolRegistry.Message<T> codec, T original) throws IOException {
        byte[] payload = codec.encode(original);
        if (!original.equals(codec.decode(payload))) {
            throw new AssertionError("Payload changed for message " + codec.id());
        }
        expectBadPayload(() -> codec.decode(Arrays.copyOf(payload, payload.length - 1)));
    }

    private static void expectBadPayload(CheckedOperation operation) throws IOException {
        try {
            operation.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected the codec to reject an invalid payload");
    }

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws IOException;
    }
}
