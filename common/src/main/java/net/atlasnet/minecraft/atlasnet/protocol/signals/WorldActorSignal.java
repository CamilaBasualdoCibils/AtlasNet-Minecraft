package net.atlasnet.minecraft.atlasnet.protocol.signals;

import java.nio.ByteBuffer;
import java.util.UUID;

/** Replicated pose of an authoritative Overworld item or arrow. */
public record WorldActorSignal(long entity, long revision, UUID uuid, byte kind,
                               double x, double y, double z, float yaw, float pitch, byte[] item) {
    public static final int SHOW = 0x4d43010e;
    public static final int UPDATE = 0x4d43010f;
    public static final byte ITEM = 1;
    public static final byte ARROW = 2;
    public static final int STATE_BYTES = 3 * Double.BYTES + 2 * Long.BYTES + 2 * Float.BYTES + 1;
    public static final int MAX_ITEM_BYTES = 8 * 1024;

    public static WorldActorSignal decode(byte[] payload) {
        var buffer = ByteBuffer.wrap(payload);
        if (buffer.remaining() < 2 * Long.BYTES + STATE_BYTES
                || buffer.remaining() > 2 * Long.BYTES + STATE_BYTES + MAX_ITEM_BYTES) {
            throw new IllegalArgumentException("Invalid AtlasNet world actor payload");
        }
        long entity = buffer.getLong();
        long revision = buffer.getLong();
        double x = buffer.getDouble(), y = buffer.getDouble(), z = buffer.getDouble();
        float yaw = buffer.getFloat(), pitch = buffer.getFloat();
        var uuid = new UUID(buffer.getLong(), buffer.getLong());
        byte kind = buffer.get();
        byte[] item = new byte[buffer.remaining()];
        buffer.get(item);
        if ((kind == ITEM && item.length == 0) || (kind == ARROW && item.length != 0)
                || (kind != ITEM && kind != ARROW)) {
            throw new IllegalArgumentException("Invalid AtlasNet world actor kind");
        }
        return new WorldActorSignal(entity, revision, uuid, kind, x, y, z, yaw, pitch, item);
    }
}
