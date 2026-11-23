package net.atlasnet.minecraft.sdk.core;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

/** Packet identifiers and connection metadata shared by Minecraft's AtlasNet providers. */
public final class MinecraftIngressPackets {
    public static final int PLAY_PACKET_SIGNAL = 0x4d43504b;
    public static final int PLAYER_ACTIVITY_SIGNAL = 0x4d435041;

    public static final int MAX_PLAYER_CHECKPOINT_BYTES = 1024 * 1024;

    private MinecraftIngressPackets() {}

    /** Writes the actual transport peer, rather than an address claimed in the Minecraft handshake. */
    public static ByteBuffer writePeer(ByteBuffer buffer, InetSocketAddress peer) {
        byte[] ip = peer.getAddress().getAddress();
        return buffer.put((byte) ip.length).put(ip).putShort((short) peer.getPort());
    }

    public static InetSocketAddress readPeer(ByteBuffer buffer) {
        int size = Byte.toUnsignedInt(buffer.get());
        if ((size != 4 && size != 16) || buffer.remaining() < size + 2) {
            throw new IllegalArgumentException("Invalid Minecraft ingress peer address");
        }
        var address = new byte[size];
        buffer.get(address);
        try {
            return new InetSocketAddress(InetAddress.getByAddress(address), Short.toUnsignedInt(buffer.getShort()));
        } catch (UnknownHostException unexpected) {
            throw new IllegalArgumentException("Invalid Minecraft ingress peer address", unexpected);
        }
    }
}
