package net.atlasnet.minecraft.atlasnet.protocol.signals;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Full authoritative pose; motion components are blocks per Minecraft tick (20 Hz). */
public record PlayerPoseSignal(long entity, long client, long revision, double x, double y, double z,
                               float yaw, float pitch, float headYaw, boolean crouching,
                               boolean sprinting, boolean onGround, boolean flying, boolean usingItem,
                               boolean usingOffhand, boolean swimming, boolean fallFlying, int mainSwing,
                               int offSwing, int mainItem, int offItem, int headItem, int chestItem,
                               int legsItem, int feetItem, float motionX, float motionY, float motionZ,
                               long motionSequence) {
    public static final int UPDATE = 0x4d430106;
    public static final int SHOW = 0x4d430107;
    public static final int HIDE = 0x4d430108;

    public static PlayerPoseSignal decode(byte[] bytes) throws IOException {
        if (bytes.length != 113) {
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
        int mainSwing = input.getInt();
        int offSwing = input.getInt();
        int mainItem = input.getInt();
        int offItem = input.getInt();
        int headItem = input.getInt();
        int chestItem = input.getInt();
        int legsItem = input.getInt();
        int feetItem = input.getInt();
        float motionX = input.getFloat();
        float motionY = input.getFloat();
        float motionZ = input.getFloat();
        long motionSequence = input.getLong();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(headYaw)
                || !Float.isFinite(motionX) || !Float.isFinite(motionY) || !Float.isFinite(motionZ)
                || motionSequence < 0
                || mainItem < 0 || offItem < 0 || headItem < 0 || chestItem < 0 || legsItem < 0 || feetItem < 0) {
            throw new IOException("Invalid player pose values");
        }
        return new PlayerPoseSignal(entity, client, revision, x, y, z, yaw, pitch, headYaw,
                (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0,
                (flags & 16) != 0, (flags & 32) != 0, (flags & 64) != 0, (flags & 128) != 0,
                mainSwing, offSwing, mainItem, offItem, headItem, chestItem, legsItem, feetItem,
                motionX, motionY, motionZ, motionSequence);
    }
}
