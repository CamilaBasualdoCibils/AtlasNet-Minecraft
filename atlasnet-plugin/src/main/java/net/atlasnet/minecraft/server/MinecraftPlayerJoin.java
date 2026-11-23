package net.atlasnet.minecraft.server;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.atlasnet.minecraft.sdk.core.MinecraftIngressPackets;
import java.util.function.Consumer;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;

/** The shard-side join boundary for a client that already acknowledged Configuration. */
public final class MinecraftPlayerJoin {
    public record InitialSpawn(ResourceLocation dimension, double x, double y, double z) {
        public InitialSpawn {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Invalid AtlasNet spawn coordinates");
        }
    }

    private MinecraftPlayerJoin() {
    }

    /**
     * Initializes the player on the Minecraft server thread using the trusted
     * admission payload and peer address supplied by ingress.
     */
    public static AtlasNetConnection place(MinecraftServer server,
                                          byte[] admissionData, Consumer<byte[]> outgoing) {
        return place(server, null, admissionData, outgoing);
    }

    public static AtlasNetConnection place(MinecraftServer server, InitialSpawn spawn,
                                          byte[] admissionData, Consumer<byte[]> outgoing) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Minecraft player initialization requires the server thread");
        }
        var admission = readAdmission(admissionData);
        var profile = admission.profile();
        var settings = admission.settings();
        var peer = admission.peer();
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
            int playId = playEntityId(profile.getId());
            if (players.getPlayers().stream().anyMatch(existing -> existing.getId() == playId))
                throw new IllegalStateException("Minecraft Play entity ID collision");
            player.setId(playId);
            var cookie = new CommonListenerCookie(profile, 0, settings, false);
            if (admission.checkpoint() != null) {
                CheckpointPlayerData.withCheckpoint(player, admission.checkpoint(),
                        () -> players.placeNewPlayer(connection, player, cookie));
            } else if (spawn != null) {
                if (server.getLevel(ResourceKey.create(Registries.DIMENSION, spawn.dimension())) == null)
                    throw new IllegalArgumentException("Unknown AtlasNet spawn dimension " + spawn.dimension());
                CheckpointPlayerData.withInitialSpawn(player, spawn,
                        () -> players.placeNewPlayer(connection, player, cookie));
            } else {
                players.placeNewPlayer(connection, player, cookie);
            }
            return connection;
        } catch (RuntimeException failure) {
            connection.closeFromAtlasNet(new DisconnectionDetails(Component.literal("Player initialization failed")));
            throw failure;
        }
    }

    public static int playEntityId(java.util.UUID player) {
        return -2 - (player.hashCode() & 0x3fff_ffff);
    }

    private record Admission(GameProfile profile, ClientInformation settings, InetSocketAddress peer,
                             CompoundTag checkpoint) {}

    private static Admission readAdmission(byte[] admissionData) {
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(admissionData));
        try {
            var profile = ByteBufCodecs.GAME_PROFILE.decode(buffer);
            var settings = new ClientInformation(buffer);
            var metadata = buffer.nioBuffer();
            var peer = MinecraftIngressPackets.readPeer(metadata);
            if (profile.getId() == null || metadata.remaining() > MinecraftIngressPackets.MAX_PLAYER_CHECKPOINT_BYTES) {
                throw new IllegalArgumentException("Invalid Minecraft admission data");
            }
            if (!metadata.hasRemaining()) return new Admission(profile, settings, peer, null);
            var encoded = new byte[metadata.remaining()];
            metadata.get(encoded);
            var checkpoint = NbtIo.readCompressed(new ByteArrayInputStream(encoded),
                    NbtAccounter.create(16L * 1024 * 1024));
            if (checkpoint.hasUUID("UUID") && !profile.getId().equals(checkpoint.getUUID("UUID"))) {
                throw new IllegalArgumentException("Player checkpoint UUID does not match admission");
            }
            return new Admission(profile, settings, peer, checkpoint);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid player checkpoint", failure);
        } finally {
            buffer.release();
        }
    }

    /** The checkpoint used by vanilla also supplies AtlasNet's initial region lookup. */
    public static java.util.Optional<InitialSpawn> checkpointSpawn(byte[] admissionData) {
        var checkpoint = readAdmission(admissionData).checkpoint();
        if (checkpoint == null) return java.util.Optional.empty();
        var position = checkpoint.getList("Pos", Tag.TAG_DOUBLE);
        if (position.size() != 3) return java.util.Optional.empty();
        var dimension = ResourceLocation.tryParse(checkpoint.getString("Dimension"));
        if (dimension == null || !checkpoint.contains("Dimension", Tag.TAG_STRING))
            dimension = net.minecraft.world.level.Level.OVERWORLD.location();
        return java.util.Optional.of(new InitialSpawn(dimension, position.getDouble(0), position.getDouble(1),
                position.getDouble(2)));
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
