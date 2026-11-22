package net.atlasnet.minecraft.server;

import io.netty.buffer.Unpooled;
import net.atlasnet.minecraft.sdk.core.MinecraftIngressPackets;
import java.util.function.Consumer;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;

/** The shard-side join boundary for a client that already acknowledged Configuration. */
public final class MinecraftPlayerJoin {
    private MinecraftPlayerJoin() {
    }

    /**
     * Initializes the player on the Minecraft server thread using the trusted
     * admission payload and peer address supplied by ingress.
     */
    public static AtlasNetConnection place(MinecraftServer server,
                                          byte[] admissionData, Consumer<byte[]> outgoing) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Minecraft player initialization requires the server thread");
        }
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(admissionData));
        try {
            var profile = ByteBufCodecs.GAME_PROFILE.decode(buffer);
            var settings = new ClientInformation(buffer);
            var metadata = buffer.nioBuffer();
            var peer = MinecraftIngressPackets.readPeer(metadata);
            if (profile.getId() == null || metadata.hasRemaining()) {
                throw new IllegalArgumentException("Invalid authenticated Minecraft admission data");
            }
            var players = server.getPlayerList();
            if (players.getPlayer(profile.getId()) != null) {
                throw new IllegalStateException(PlayerList.DUPLICATE_LOGIN_DISCONNECT_MESSAGE.getString());
            }
            Component rejection = players.canPlayerLogin(peer, profile);
            if (rejection != null) {
                throw new IllegalStateException(rejection.getString());
            }
            var connection = new AtlasNetConnection(peer, server.registryAccess(), outgoing);
            try {
                var player = players.getPlayerForLogin(profile, settings);
                players.placeNewPlayer(connection, player, new CommonListenerCookie(profile, 0, settings, false));
                return connection;
            } catch (RuntimeException failure) {
                connection.closeFromAtlasNet(new DisconnectionDetails(Component.literal("Player initialization failed")));
                throw failure;
            }
        } finally {
            buffer.release();
        }
    }
}
