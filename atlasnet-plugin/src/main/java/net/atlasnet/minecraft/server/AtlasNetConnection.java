package net.atlasnet.minecraft.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * A vanilla Play connection whose wire boundary is an AtlasNet packet body.
 * Create and operate it on the Minecraft server thread.
 */
public final class AtlasNetConnection extends Connection {
    private static final System.Logger LOG = System.getLogger(AtlasNetConnection.class.getName());
    private static final int MAX_PENDING_INPUT = 512;
    private final EmbeddedChannel transport;
    private final SocketAddress peer;
    private boolean disposed;
    private boolean restoringPlay;
    private final ArrayDeque<Packet<?>> pendingInput = new ArrayDeque<>();
    private Integer initialTeleport;
    private Long keepAlive;
    private Predicate<Packet<?>> intercept = packet -> false;

    /** The outgoing sink accepts a complete vanilla packet body, including its packet ID. */
    public AtlasNetConnection(SocketAddress peer, RegistryAccess registries, Consumer<byte[]> outgoing) {
        super(PacketFlow.SERVERBOUND);
        this.peer = Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(outgoing, "outgoing");
        transport = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
                var pipeline = channel.pipeline();
                // Minecraft still encodes and decodes Packets. The no-op frame handlers
                // preserve packet boundaries already supplied by the AtlasNet message.
                Connection.configureSerialization(pipeline, PacketFlow.SERVERBOUND, true, null);
                // Login and Configuration already finished on the ingress Node.
                pipeline.replace("decoder", "inbound_config", new UnconfiguredPipelineHandler.Inbound());
                configurePacketHandler(pipeline);
                pipeline.addBefore("packet_handler", "atlasnet_pending_input",
                        new io.netty.channel.ChannelInboundHandlerAdapter() {
                            @Override public void channelRead(ChannelHandlerContext context, Object message) {
                                if (message instanceof Packet<?> packet) {
                                    if (pendingInput.size() < MAX_PENDING_INPUT) {
                                        pendingInput.addLast(packet);
                                    } else {
                                        disconnect(net.minecraft.network.chat.Component.literal("Too much queued Minecraft input"));
                                    }
                                } else {
                                    context.fireChannelRead(message);
                                }
                            }
                        });
                pipeline.addBefore("packet_handler", "atlasnet_keep_alive",
                        new io.netty.channel.ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext context, Object message) {
                                // A response to the former shard's challenge can be in flight during transfer.
                                if (message instanceof ServerboundKeepAlivePacket response) {
                                    if (keepAlive == null || response.getId() != keepAlive) return;
                                    keepAlive = null;
                                }
                                if (message instanceof Packet<?> packet && intercept.test(packet)) return;
                                context.fireChannelRead(message);
                            }
                        });
                pipeline.addFirst(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                        if (!(message instanceof ByteBuf buffer)) {
                            context.write(message, promise);
                            return;
                        }
                        try {
                            var packet = new byte[buffer.readableBytes()];
                            buffer.readBytes(packet);
                            outgoing.accept(packet);
                            promise.setSuccess();
                        } catch (RuntimeException failure) {
                            promise.setFailure(failure);
                        } finally {
                            ReferenceCountUtil.release(buffer);
                        }
                    }
                });
            }
        });
        setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries)));
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return peer;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        LOG.log(System.Logger.Level.WARNING, "Embedded Minecraft connection failed", cause);
        super.exceptionCaught(context, cause);
    }

    /** Reuse normal server-side placement without replaying client-side join or teleport. */
    void restorePlay(Runnable placePlayer) {
        restoringPlay = true;
        try {
            placePlayer.run();
        } finally {
            restoringPlay = false;
        }
        if (initialTeleport != null && getPacketListener() instanceof ServerGamePacketListenerImpl play) {
            play.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(initialTeleport));
            initialTeleport = null;
        }
    }

    /** Preserve packet order while giving vanilla regular tick boundaries between movements. */
    void tickInput() {
        var context = transport.pipeline().context("atlasnet_pending_input");
        int moves = 0;
        while (!pendingInput.isEmpty() && isConnected()) {
            if (pendingInput.peekFirst() instanceof ServerboundMovePlayerPacket && ++moves > 2) break;
            context.fireChannelRead(pendingInput.removeFirst());
        }
    }

    boolean hasPendingInput() {
        return !pendingInput.isEmpty();
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener completion, boolean flush) {
        if (disposed) return;
        // The shard bridge publishes session lifecycle messages as cluster Signals.
        if (packet instanceof ClientboundSystemChatPacket chat
                && chat.content().getContents() instanceof TranslatableContents translated) {
            switch (translated.getKey()) {
                case "multiplayer.player.joined", "multiplayer.player.joined.renamed", "multiplayer.player.left" -> {
                    return;
                }
                default -> {}
            }
        }
        if (restoringPlay) {
            if (packet instanceof ClientboundLoginPacket) return;
            if (packet instanceof ClientboundPlayerPositionPacket position) {
                initialTeleport = position.getId();
                return;
            }
        }
        if (packet instanceof ClientboundKeepAlivePacket challenge) keepAlive = challenge.getId();
        super.send(packet, completion, flush);
    }

    /** Feed a complete packet body into the active vanilla listener. */
    public void receivePacket(byte[] packet) {
        if (transport.isActive()) transport.writeInbound(Unpooled.wrappedBuffer(packet));
    }

    void interceptPlayPackets(Predicate<Packet<?>> handler) {
        intercept = Objects.requireNonNull(handler);
    }

    /** Called when AtlasNet closes the connection, after the last packet is delivered. */
    public void closeFromAtlasNet(DisconnectionDetails details) {
        if (disposed) {
            return;
        }
        disposed = true;
        disconnect(details);
        transport.finishAndReleaseAll();
        handleDisconnection();
    }
}
