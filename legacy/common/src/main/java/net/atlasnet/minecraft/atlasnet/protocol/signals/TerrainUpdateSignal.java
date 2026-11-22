package net.atlasnet.minecraft.atlasnet.protocol.signals;

import java.nio.ByteBuffer;

/** A vanilla terrain packet for one of the viewers selected by Minecraft's chunk tracker. */
public record TerrainUpdateSignal(int chunkX, int chunkZ, int viewer, byte[] packet) {
    public static final int ID = 0x4d430109;
    public static final int HEADER_BYTES = 3 * Integer.BYTES;
    public static final int MAX_BYTES = 4096;

    public TerrainUpdateSignal {
        if (packet.length == 0 || packet.length > MAX_BYTES - HEADER_BYTES) {
            throw new IllegalArgumentException("Invalid AtlasNet terrain packet size");
        }
    }

    public byte[] encode() {
        return ByteBuffer.allocate(HEADER_BYTES + packet.length)
                .putInt(chunkX).putInt(chunkZ).putInt(viewer).put(packet).array();
    }

    public static TerrainUpdateSignal decode(byte[] bytes) {
        if (bytes.length <= HEADER_BYTES || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Invalid AtlasNet terrain update size");
        }
        var buffer = ByteBuffer.wrap(bytes);
        int x = buffer.getInt();
        int z = buffer.getInt();
        int viewer = buffer.getInt();
        byte[] packet = new byte[buffer.remaining()];
        buffer.get(packet);
        return new TerrainUpdateSignal(x, z, viewer, packet);
    }
}
