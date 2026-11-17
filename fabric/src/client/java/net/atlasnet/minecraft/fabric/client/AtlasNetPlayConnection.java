package net.atlasnet.minecraft.fabric.client;

import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.io.IOException;
import net.atlasnet.minecraft.atlasnet.protocol.ProtocolRegistry;
import net.atlasnet.minecraft.atlasnet.protocol.commands.BlockInteractionCommand;
import net.atlasnet.minecraft.atlasnet.protocol.commands.GameplayInputCommand;
import net.atlasnet.minecraft.atlasnet.protocol.commands.PlayerAttackCommand;
import net.atlasnet.minecraft.atlasnet.protocol.signals.WorldActorSignal;
import net.atlasnet.minecraft.fabric.mixin.InteractPacketAccessor;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.atlasnet.minecraft.atlasnet.protocol.commands.ChatCommand;
import net.atlasnet.minecraft.atlasnet.protocol.commands.PlayerInputCommand;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerRegionSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerPoseSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.TerrainUpdateSignal;
import net.atlasnet.minecraft.atlasnet.protocol.signals.TerrainSnapshotSignal;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.atlasnet.minecraft.fabric.mixin.EntityRemovalAccessor;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.atlasnet.minecraft.DemoStartup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.server.ServerLinks;
import net.minecraft.world.flag.FeatureFlags;

public final class AtlasNetPlayConnection extends Connection {
    record Command(int id, byte[] payload) {}

    private final Minecraft minecraft;
    private final RemotePlayers remotePlayers = new RemotePlayers();
    private final RemoteWorldActors worldActors = new RemoteWorldActors();
    private final TerrainSnapshotReceiver terrainSnapshots = new TerrainSnapshotReceiver();
    private final Map<Long, PlayerPoseSignal> pendingVisibility = new java.util.HashMap<>();
    private final Runnable close;
    private final ConcurrentLinkedQueue<Command> outbound = new ConcurrentLinkedQueue<>();
    private ProtocolInfo<?> outboundProtocol;
    private ProtocolInfo<?> protocol;
    private PacketListener listener;
    private boolean connected = true;
    private boolean showRegion;
    private boolean showMetrics = true;
    private boolean worldReady;
    private long playEpoch;
    private boolean awaitingHandoffLogin;
    private boolean awaitingHandoffPosition;
    private PlayerRegionSignal region;
    private String shardLabel;

    AtlasNetPlayConnection(Minecraft minecraft, Screen parent, ServerData server, Runnable close) {
        super(PacketFlow.CLIENTBOUND);
        this.close = close;
        this.minecraft = minecraft;
        var telemetry = minecraft.getTelemetryManager().createWorldSessionManager(false, null, null);
        var cookie = new CommonListenerCookie(minecraft.getGameProfile(), telemetry, RegistryAccess.EMPTY,
                FeatureFlags.DEFAULT_FLAGS, "AtlasNet", server, parent, Map.of(), null,
                false, Map.of(), ServerLinks.EMPTY);
        setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND,
                new ClientConfigurationPacketListenerImpl(minecraft, this, cookie));
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> nextProtocol, T nextListener) {
        protocol = nextProtocol;
        listener = nextListener;
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> nextProtocol) {
        outboundProtocol = nextProtocol;
    }

    @Override
    public PacketListener getPacketListener() {
        return listener;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void tick() {
        if (listener instanceof TickablePacketListener tickable) {
            tickable.tick();
        }
        terrainSnapshots.apply(minecraft.level, this::receiveGamePacket);
        worldActors.attach(minecraft.level);
        if (minecraft.level != null && !pendingVisibility.isEmpty()) {
            pendingVisibility.values().forEach(pose -> remotePlayers.apply(minecraft.level, pose, true));
            pendingVisibility.clear();
        }
        if (!worldReady && minecraft.level != null && minecraft.player != null
                && !(minecraft.screen instanceof ReceivingLevelScreen)
                && minecraft.level.hasChunkAt(minecraft.player.blockPosition())) {
            worldReady = true;
            DemoStartup.log("AtlasNet world ready");
        }
    }

    @Override
    public void flushChannel() {}

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void send(Packet<?> packet, PacketSendListener callback, boolean flush) {
        if (packet instanceof ServerboundInteractPacket interaction) {
            var target = remotePlayers.atlasEntity(((InteractPacketAccessor) interaction).atlasnet$entityId());
            if (target != null) {
                forwardRemoteInteraction(interaction, target);
                return;
            }
        }
        boolean playerInput = PlayerInputCommand.supports(packet);
        boolean blockInteraction = BlockInteractionCommand.supports(packet);
        boolean gameplayInput = GameplayInputCommand.supports(packet);
        if (outboundProtocol == null || !(playerInput || blockInteraction || gameplayInput
                || packet instanceof ServerboundKeepAlivePacket
                || packet instanceof ServerboundAcceptTeleportationPacket
                || packet instanceof ServerboundChunkBatchReceivedPacket)) {
            return;
        }
        var buffer = Unpooled.buffer();
        try {
            buffer.writeLong(playEpoch);
            ((ProtocolInfo) outboundProtocol).codec().encode(buffer, packet);
            if (gameplayInput && buffer.readableBytes() - Long.BYTES > GameplayInputCommand.MAX_PACKET_BYTES) {
                return;
            }
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            int id = gameplayInput ? GameplayInputCommand.ID : blockInteraction ? BlockInteractionCommand.ID
                    : playerInput ? PlayerInputCommand.ID : 0x4d430003;
            outbound.add(new Command(id, bytes));
        } finally {
            buffer.release();
        }
    }

    private void forwardRemoteInteraction(ServerboundInteractPacket interaction, long target) {
        interaction.dispatch(new ServerboundInteractPacket.Handler() {
            @Override public void onInteraction(InteractionHand hand) {}
            @Override public void onInteraction(InteractionHand hand, Vec3 position) {}
            @Override public void onAttack() {
                byte[] bytes = ByteBuffer.allocate(2 * Long.BYTES).putLong(playEpoch).putLong(target).array();
                outbound.add(new Command(PlayerAttackCommand.ID, bytes));
            }
        });
    }

    Command nextOutbound() {
        return outbound.poll();
    }

    @Override
    public void disconnect(Component reason) {
        disconnect(new DisconnectionDetails(reason));
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
        connected = false;
        close.run();
    }

    public PlayerRegionSignal visibleRegion() {
        return connected && showRegion ? region : null;
    }

    public String visibleShardLabel() {
        return connected && showMetrics ? shardLabel : null;
    }

    public void command(String text) {
        switch (text) {
            case "atlasnet client showRegion true" -> showRegion = true;
            case "atlasnet client showRegion false" -> showRegion = false;
            case "atlasnet client metrics true" -> showMetrics = true;
            case "atlasnet client metrics false" -> showMetrics = false;
            default -> minecraft.gui.getChat().addMessage(Component.literal(
                    "Use /atlasnet client metrics true|false or /atlasnet client showRegion true|false"));
        }
    }

    public static AtlasNetPlayConnection current() {
        var listener = Minecraft.getInstance().getConnection();
        return listener != null && listener.getConnection() instanceof AtlasNetPlayConnection connection
                ? connection : null;
    }

    public void chat(String message) {
        try {
            var codec = ProtocolRegistry.CHAT_COMMAND;
            outbound.add(new Command(codec.id(), codec.encode(new ChatCommand(message))));
        } catch (IOException error) {
            minecraft.gui.getChat().addMessage(Component.literal(error.getMessage()));
        }
    }

    void receive(byte[] bytes) {
        if (!connected) {
            return;
        }
        int id = ByteBuffer.wrap(bytes).getInt();
        byte[] payload = Arrays.copyOfRange(bytes, 4, bytes.length);
        if (id == 0x4d43010d && payload.length == Long.BYTES) {
            playEpoch = ByteBuffer.wrap(payload).getLong();
            awaitingHandoffLogin = true;
            terrainSnapshots.clear();
        } else if (id == TerrainSnapshotSignal.VIEWER_ID && payload.length == Long.BYTES) {
            terrainSnapshots.setViewer(ByteBuffer.wrap(payload).getLong());
        } else if (id == TerrainSnapshotSignal.ID) {
            terrainSnapshots.accept(TerrainSnapshotSignal.decode(payload));
        } else if (id == TerrainUpdateSignal.ID) {
            var update = TerrainUpdateSignal.decode(payload);
            if (minecraft.player != null && minecraft.player.getId() == update.viewer()
                    && minecraft.level != null && minecraft.level.hasChunk(update.chunkX(), update.chunkZ())) {
                receiveGamePacket(update.packet());
            }
        } else if (id == 0x4d430105) {
            receiveGamePacket(payload);
        } else {
            receiveNotice(id, payload);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void receiveGamePacket(byte[] payload) {
        var buffer = Unpooled.wrappedBuffer(payload);
        try {
            Packet packet = protocol.codec().decode(buffer);
            if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
                terrainSnapshots.forget(forget.pos());
            }
            if (awaitingHandoffLogin && packet instanceof ClientboundLoginPacket login) {
                applyHandoff(login);
                awaitingHandoffLogin = false;
                awaitingHandoffPosition = true;
                return;
            }
            if (awaitingHandoffPosition && packet instanceof ClientboundPlayerPositionPacket position) {
                awaitingHandoffPosition = false;
                if (continueLocalMotion(position)) {
                    return;
                }
            }
            if (packet instanceof ClientboundAddEntityPacket add && add.getType() == EntityType.PLAYER) {
                remotePlayers.removeMatchingPlayer(minecraft.level, add.getUUID());
            }
            packet.handle(listener);
        } finally {
            buffer.release();
        }
    }

    private void applyHandoff(ClientboundLoginPacket login) {
        var level = minecraft.level;
        var player = minecraft.player;
        if (level == null || player == null || minecraft.gameMode == null
                || !level.dimension().equals(login.commonPlayerSpawnInfo().dimension())) {
            throw new IllegalStateException("AtlasNet handoff must continue in the existing client world");
        }
        // Vanilla entity numbers are local to a worker. AtlasNet replicas use
        // negative numbers and remain visible across the handoff.
        var previousEntities = new java.util.ArrayList<Integer>();
        for (var entity : level.entitiesForRendering()) {
            if (entity != player && entity.getId() >= 0) {
                previousEntities.add(entity.getId());
            }
        }
        for (int id : previousEntities) {
            level.removeEntity(id, Entity.RemovalReason.DISCARDED);
        }
        if (player.getId() != login.playerId()) {
            level.removeEntity(player.getId(), Entity.RemovalReason.DISCARDED);
            ((EntityRemovalAccessor) player).atlasnetRestoreEntity();
            player.setId(login.playerId());
            level.addEntity(player);
        }
        var info = login.commonPlayerSpawnInfo();
        minecraft.gameMode.setLocalMode(info.gameType(), info.previousGameType());
        player.setReducedDebugInfo(login.reducedDebugInfo());
        player.setShowDeathScreen(login.showDeathScreen());
        player.setDoLimitedCrafting(login.doLimitedCrafting());
        player.setLastDeathLocation(info.lastDeathLocation());
        player.setPortalCooldown(info.portalCooldown());
    }

    private boolean continueLocalMotion(ClientboundPlayerPositionPacket position) {
        var player = minecraft.player;
        if (!position.getRelativeArguments().isEmpty()
                || player.distanceToSqr(position.getX(), position.getY(), position.getZ()) > 4.0) {
            return false;
        }
        // The destination restores the last accepted pose. Keep ordinary
        // client prediction and acknowledge the destination's new listener.
        send(new ServerboundAcceptTeleportationPacket(position.getId()));
        send(new ServerboundMovePlayerPacket.PosRot(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), player.onGround()));
        return true;
    }

    private void receiveNotice(int id, byte[] payload) {
        try {
            if (id == PlayerPoseSignal.SHOW || id == PlayerPoseSignal.UPDATE) {
                var pose = PlayerPoseSignal.decode(payload);
                if (minecraft.level != null) {
                    remotePlayers.apply(minecraft.level, pose, id == PlayerPoseSignal.SHOW);
                } else if (id == PlayerPoseSignal.SHOW) {
                    pendingVisibility.put(pose.entity(), pose);
                }
            } else if (id == PlayerPoseSignal.HIDE && payload.length == Long.BYTES) {
                long entity = ByteBuffer.wrap(payload).getLong();
                pendingVisibility.remove(entity);
                remotePlayers.remove(minecraft.level, entity);
                worldActors.remove(minecraft.level, entity);
            } else if (id == WorldActorSignal.SHOW || id == WorldActorSignal.UPDATE) {
                worldActors.apply(minecraft.level, WorldActorSignal.decode(payload), id == WorldActorSignal.SHOW);
            } else if (id == ProtocolRegistry.PLAYER_REGION.id()) {
                region = ProtocolRegistry.PLAYER_REGION.decode(payload);
            } else if (id == ProtocolRegistry.SHARD_METRICS.id()) {
                shardLabel = "Shard " + ProtocolRegistry.SHARD_METRICS.decode(payload).shardId();
            } else if (id == ProtocolRegistry.CHAT.id()) {
                var chat = ProtocolRegistry.CHAT.decode(payload);
                minecraft.gui.getChat().addMessage(Component.literal("<" + chat.displayName() + "> " + chat.text()));
            } else if (id == ProtocolRegistry.PLAYER_JOINED.id()) {
                var player = ProtocolRegistry.PLAYER_JOINED.decode(payload);
                minecraft.gui.getChat().addMessage(Component.translatable("multiplayer.player.joined", player.displayName()));
            } else if (id == ProtocolRegistry.PLAYER_LEFT.id()) {
                var player = ProtocolRegistry.PLAYER_LEFT.decode(payload);
                minecraft.gui.getChat().addMessage(Component.translatable("multiplayer.player.left", player.displayName()));
            }
        } catch (IOException | IllegalArgumentException error) {
            minecraft.gui.getChat().addMessage(Component.literal("Invalid AtlasNet notification"));
        }
    }
}
