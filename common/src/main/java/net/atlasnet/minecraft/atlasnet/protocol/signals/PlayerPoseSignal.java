package net.atlasnet.minecraft.atlasnet.protocol.signals;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Full authoritative pose shared by remote player initialization and movement updates. */
public record PlayerPoseSignal(long entity, long client, long revision, double x, double y, double z,
                               float yaw, float pitch, float headYaw, boolean crouching,
                               boolean sprinting, boolean onGround) {
    public static final int UPDATE = 0x4d430106;
    public static final int SHOW = 0x4d430107;
    public static final int HIDE = 0x4d430108;

    public static PlayerPoseSignal decode(byte[] bytes) throws IOException {
        if (bytes.length != 61) {
            throw new IOException("Invalid player pose size");
        }
        var input = ByteBuffer.wrap(bytes);
        long entity = input.getLong();
        long client = input.getLong();
        long revision = input.getLong();
        double x = input.getDouble();
        double y = input.getDouble();
        double z = input.getDouble();
        float yaw = input.getFloat();
        float pitch = input.getFloat();
        float headYaw = input.getFloat();
        int flags = input.get() & 0xff;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(headYaw)
                || flags > 7) {
            throw new IOException("Invalid player pose values");
        }
        return new PlayerPoseSignal(entity, client, revision, x, y, z, yaw, pitch, headYaw,
                (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0);
    }
}
