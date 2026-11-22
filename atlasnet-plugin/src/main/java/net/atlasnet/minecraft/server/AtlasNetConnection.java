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
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;

/**
 * A vanilla Play connection whose wire boundary is an AtlasNet packet body.
 * Create and operate it on the Minecraft server thread.
 */
public final class AtlasNetConnection extends Connection {
    private final EmbeddedChannel transport;
    private final SocketAddress peer;
    private boolean disposed;

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

    /** Call after PlayerList.placeNewPlayer installs the vanilla inbound Play listener. */
    public void receivePacket(byte[] packet) {
        if (transport.isActive()) {
            transport.writeInbound(Unpooled.wrappedBuffer(packet));
        }
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
