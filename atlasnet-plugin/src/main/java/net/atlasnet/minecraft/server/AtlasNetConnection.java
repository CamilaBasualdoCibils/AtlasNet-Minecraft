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
import java.util.Objects;
import java.util.function.Consumer;
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
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * A vanilla Play connection whose wire boundary is an AtlasNet packet body.
 * Create and operate it on the Minecraft server thread.
 */
public final class AtlasNetConnection extends Connection {
    private final EmbeddedChannel transport;
    private final SocketAddress peer;
    private boolean disposed;
    private boolean restoringPlay;
    private Integer initialTeleport;
    private Long keepAlive;

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
                pipeline.addBefore("packet_handler", "atlasnet_keep_alive",
                        new io.netty.channel.ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext context, Object message) {
                                // A response to the former shard's challenge can be in flight during transfer.
                                if (message instanceof ServerboundKeepAlivePacket response) {
                                    if (keepAlive == null || response.getId() != keepAlive) return;
                                    keepAlive = null;
                                }
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

    @Override
    public void send(Packet<?> packet, PacketSendListener completion, boolean flush) {
        if (disposed) return;
        if (restoringPlay) {
            if (packet instanceof ClientboundLoginPacket) return;
            if (packet instanceof ClientboundPlayerPositionPacket position) {
                initialTeleport = position.getId();
                return;
            }
            if (packet instanceof ClientboundSystemChatPacket chat &&
                    chat.content().getContents() instanceof TranslatableContents translated &&
                    (translated.getKey().equals("multiplayer.player.joined") ||
                            translated.getKey().equals("multiplayer.player.joined.renamed"))) return;
        }
        if (packet instanceof ClientboundKeepAlivePacket challenge) keepAlive = challenge.getId();
        super.send(packet, completion, flush);
    }

    /** Feed a complete packet body into the active vanilla listener. */
    public void receivePacket(byte[] packet) {
        if (transport.isActive()) transport.writeInbound(Unpooled.wrappedBuffer(packet));
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
