package net.atlasnet.minecraft.atlasnet.protocol;

import net.atlasnet.minecraft.atlasnet.protocol.commands.ChatCommand;
import net.atlasnet.minecraft.atlasnet.protocol.signals.ChatSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerJoinedSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerLeftSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerRegionSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerRegionSignal.Plane;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerRegionSignal.Point;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerRegionSignal.Shape;
import net.atlasnet.minecraft.atlasnet.protocol.signals.ShardMetricsSignal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

/** Application IDs and payload codecs for AtlasNet raw Commands and Signals. */
public final class ProtocolRegistry {
    private static final int MAX_TEXT_BYTES = 1024;
    private static final int MAX_NAME_BYTES = 256;
    private static final int MAX_PLANES = 4096;

    public static final Message<ChatCommand> CHAT_COMMAND = new Message<>(0x4d430001,
            (out, value) -> writeString(out, value.text(), MAX_TEXT_BYTES),
            in -> new ChatCommand(readString(in, MAX_TEXT_BYTES)));

    public static final Message<ChatSignal> CHAT = new Message<>(0x4d430101,
            (out, value) -> {
                writePlayer(out, value.entityId(), value.displayName());
                writeString(out, value.text(), MAX_TEXT_BYTES);
            },
            in -> new ChatSignal(in.readLong(), readString(in, MAX_NAME_BYTES),
                    readString(in, MAX_TEXT_BYTES)));

    public static final Message<PlayerJoinedSignal> PLAYER_JOINED = new Message<>(0x4d430102,
            (out, value) -> writePlayer(out, value.entityId(), value.displayName()),
            in -> new PlayerJoinedSignal(in.readLong(), readString(in, MAX_NAME_BYTES)));

    public static final Message<PlayerLeftSignal> PLAYER_LEFT = new Message<>(0x4d430103,
            (out, value) -> writePlayer(out, value.entityId(), value.displayName()),
            in -> new PlayerLeftSignal(in.readLong(), readString(in, MAX_NAME_BYTES)));

    public static final Message<PlayerRegionSignal> PLAYER_REGION = new Message<>(0x4d430104,
            ProtocolRegistry::writeRegion, ProtocolRegistry::readRegion);

    public static final Message<ShardMetricsSignal> SHARD_METRICS = new Message<>(0x4d43010c,
            (out, value) -> {
                out.writeLong(value.shardId().getMostSignificantBits());
                out.writeLong(value.shardId().getLeastSignificantBits());
            },
            in -> new ShardMetricsSignal(new UUID(in.readLong(), in.readLong())));

    private ProtocolRegistry() {
    }

    public static final class Message<T> {
        private final int id;
        private final Encoder<T> encoder;
        private final Decoder<T> decoder;

        private Message(int id, Encoder<T> encoder, Decoder<T> decoder) {
            this.id = id;
            this.encoder = encoder;
            this.decoder = decoder;
        }

        public int id() {
            return id;
        }

        public byte[] encode(T message) throws IOException {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                encoder.write(output, message);
            }
            return bytes.toByteArray();
        }

        public T decode(byte[] payload) throws IOException {
            try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
                T message = decoder.read(input);
                if (input.read() != -1) {
                    throw new IOException("Trailing bytes in AtlasNet message");
                }
                return message;
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid AtlasNet message", invalid);
            }
        }
    }

    @FunctionalInterface
    private interface Encoder<T> {
        void write(DataOutputStream out, T value) throws IOException;
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T read(DataInputStream in) throws IOException;
    }

    private static void writePlayer(DataOutputStream out, long entityId, String name) throws IOException {
        out.writeLong(entityId);
        writeString(out, name, MAX_NAME_BYTES);
    }

    private static void writeString(DataOutputStream out, String value, int limit) throws IOException {
        ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
        if (bytes.remaining() > limit) {
            throw new IOException("AtlasNet text exceeds payload limit");
        }
        out.writeShort(bytes.remaining());
        while (bytes.hasRemaining()) {
            out.writeByte(bytes.get());
        }
    }

    private static String readString(DataInputStream in, int limit) throws IOException {
        int length = in.readUnsignedShort();
        if (length > limit) {
            throw new IOException("AtlasNet text exceeds payload limit");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void writeRegion(DataOutputStream out, PlayerRegionSignal value) throws IOException {
        out.writeLong(value.regionId());
        out.writeLong(value.generation());
        Shape shape = value.shape();
        if (shape == null) {
            out.writeByte(0);
            return;
        }
        if (shape.planes().size() > MAX_PLANES) {
            throw new IOException("AtlasNet region exceeds plane limit");
        }
        out.writeByte(shape.dimension());
        writePoint(out, shape.min());
        writePoint(out, shape.max());
        out.writeShort(shape.planes().size());
        for (Plane plane : shape.planes()) {
            writePoint(out, plane.normal());
            out.writeDouble(plane.offset());
            out.writeBoolean(plane.exclusive());
        }
    }

    private static PlayerRegionSignal readRegion(DataInputStream in) throws IOException {
        long id = in.readLong();
        long generation = in.readLong();
        int dimension = in.readUnsignedByte();
        if (dimension == 0) {
            return new PlayerRegionSignal(id, generation, null);
        }
        Point min = readPoint(in);
        Point max = readPoint(in);
        int count = in.readUnsignedShort();
        if (count > MAX_PLANES) {
            throw new IOException("AtlasNet region exceeds plane limit");
        }
        var planes = new ArrayList<Plane>(count);
        for (int i = 0; i < count; i++) {
            planes.add(new Plane(readPoint(in), in.readDouble(), in.readBoolean()));
        }
        return new PlayerRegionSignal(id, generation, new Shape(dimension, min, max, planes));
    }

    private static void writePoint(DataOutputStream out, Point point) throws IOException {
        out.writeDouble(point.x());
        out.writeDouble(point.y());
        out.writeDouble(point.z());
    }

    private static Point readPoint(DataInputStream in) throws IOException {
        return new Point(in.readDouble(), in.readDouble(), in.readDouble());
    }
}
