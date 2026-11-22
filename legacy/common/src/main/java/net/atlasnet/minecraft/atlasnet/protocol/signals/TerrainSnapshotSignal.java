package net.atlasnet.minecraft.atlasnet.protocol.signals;

import java.nio.ByteBuffer;

/** One fragment of an authoritative Minecraft chunk packet. */
public record TerrainSnapshotSignal(long viewer, long sourceHigh, long sourceLow, long sequence,
                                    int x, int z, int offset, int size, byte[] bytes) {
    public static final int VIEWER_ID = 0x4d43010a;
    public static final int ID = 0x4d43010b;
    private static final int HEADER_SIZE = 4 * Long.BYTES + 4 * Integer.BYTES;
    public static final int MAX_PACKET = 8 * 1024 * 1024;

    public static TerrainSnapshotSignal decode(byte[] payload) {
        if (payload.length <= HEADER_SIZE || payload.length > TerrainUpdateSignal.MAX_BYTES) {
            throw new IllegalArgumentException("Invalid chunk snapshot fragment");
        }
        var in = ByteBuffer.wrap(payload);
        long viewer = in.getLong();
        long sourceHigh = in.getLong();
        long sourceLow = in.getLong();
        long sequence = in.getLong();
        int x = in.getInt();
        int z = in.getInt();
        int offset = in.getInt();
        int size = in.getInt();
        if (offset < 0 || size > MAX_PACKET || size - (long) offset < in.remaining()) {
            throw new IllegalArgumentException("Invalid chunk snapshot range");
        }
        byte[] bytes = new byte[in.remaining()];
        in.get(bytes);
        return new TerrainSnapshotSignal(viewer, sourceHigh, sourceLow, sequence, x, z, offset, size, bytes);
    }
}
