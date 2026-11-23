package net.atlasnet.minecraft.server;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
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
            if (profile.getId() == null || metadata.remaining() > MinecraftIngressPackets.MAX_PLAYER_CHECKPOINT_BYTES) {
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
                // The client keeps this ID across shards. Use the negative range so it cannot
                // collide with ordinary vanilla entity IDs allocated on the destination.
                int playId = -2 - (profile.getId().hashCode() & 0x3fff_ffff);
                if (players.getPlayers().stream().anyMatch(existing -> existing.getId() == playId))
                    throw new IllegalStateException("Minecraft Play entity ID collision");
                player.setId(playId);
                var cookie = new CommonListenerCookie(profile, 0, settings, false);
                if (metadata.hasRemaining()) {
                    var encoded = new byte[metadata.remaining()];
                    metadata.get(encoded);
                    try {
                        var checkpoint = NbtIo.readCompressed(new ByteArrayInputStream(encoded),
                                NbtAccounter.create(16L * 1024 * 1024));
                        if (checkpoint.hasUUID("UUID") && !profile.getId().equals(checkpoint.getUUID("UUID"))) {
                            throw new IllegalArgumentException("Player checkpoint UUID does not match admission");
                        }
                        CheckpointPlayerData.withCheckpoint(player, checkpoint,
                                () -> players.placeNewPlayer(connection, player, cookie));
                    } catch (IOException failure) {
                        throw new IllegalArgumentException("Invalid player checkpoint", failure);
                    }
                } else {
                    players.placeNewPlayer(connection, player, cookie);
                }
                return connection;
            } catch (RuntimeException failure) {
                connection.closeFromAtlasNet(new DisconnectionDetails(Component.literal("Player initialization failed")));
                throw failure;
            }
        } finally {
            buffer.release();
        }
    }

    /** Attach a restored player to the existing endpoint session without changing protocol phase. */
    static AtlasNetConnection resumePlay(MinecraftServer server, GameProfile profile,
                                         ClientInformation information, InetSocketAddress peer, int playId,
                                         CompoundTag state, Consumer<byte[]> outgoing) {
        if (!server.isSameThread()) throw new IllegalStateException("Transfer requires the server thread");
        if (profile.getId() == null || !state.hasUUID("UUID") || !profile.getId().equals(state.getUUID("UUID")))
            throw new IllegalArgumentException("Player transfer UUID does not match the authenticated profile");
        var players = server.getPlayerList();
        var player = players.getPlayerForLogin(profile, information);
        player.setId(playId);
        var connection = new AtlasNetConnection(peer, server.registryAccess(), outgoing);
        var cookie = new CommonListenerCookie(profile, 0, information, true);
        connection.restorePlay(() -> CheckpointPlayerData.withCheckpoint(player, state,
                () -> players.placeNewPlayer(connection, player, cookie)));
        return connection;
    }
}
