package net.atlasnet.minecraft.endpoint;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.atlasnet.minecraft.sdk.core.MinecraftIngressPackets;
import org.atlasnet.atlasnet.ClientAdmissionTicket;
import org.atlasnet.atlasnet.ClientConnectStatus;
import org.atlasnet.atlasnet.ClientDispatchResult;
import org.atlasnet.atlasnet.ClientEndpoint;
import org.atlasnet.atlasnet.ClientHeaderVersion;
import org.atlasnet.atlasnet.ClientInstruction;
import org.atlasnet.atlasnet.ClientPacketSubmission;
import org.atlasnet.atlasnet.PacketHeader;
import org.atlasnet.atlasnet.Registry;
import org.javagi.base.GErrorException;

/** Listens for Minecraft 1.21.1 TCP clients and delegates online-mode admission to AtlasNet. */
public final class MinecraftTcpEndpoint {
    /** AtlasNet Signal identifier for an unframed clientbound Minecraft Play packet. */
    public static final int PLAY_PACKET_SIGNAL = MinecraftIngressPackets.PLAY_PACKET_SIGNAL;
    public static final int DEFAULT_PORT = 25565;
    private static final int PROTOCOL = 767;
    private static final int ATLASNET_V1_MAGIC = 0x41544c43;
    private static final int MAX_PENDING_BYTES = 8 * 1024 * 1024;
    private static final System.Logger LOG = System.getLogger(MinecraftTcpEndpoint.class.getName());

    private MinecraftTcpEndpoint() {}

    public static void register(Registry registry, String endpointName, String admissionName,
                                String spawnName, Consumer<IOException> startupFailure)
            throws GErrorException {
        register(registry, endpointName, new InetSocketAddress(DEFAULT_PORT),
                admissionName, spawnName, startupFailure);
    }

    /** Called by the JVM host before the Node starts. */
    public static void register(Registry registry, String endpointName, InetSocketAddress bind,
                                String admissionName, String spawnName, Consumer<IOException> startupFailure)
            throws GErrorException {
        registry.registerClientEndpoint(endpointName, (id, name, arguments) -> {
            var listener = new Listener(bind, admissionName, spawnName, startupFailure);
            return listener.endpoint;
        });
    }

    private static final class Listener {
        final ClientEndpoint endpoint = new ClientEndpoint();
        final Set<Peer> peers = ConcurrentHashMap.newKeySet();
        final ConcurrentHashMap<Long, Peer> active = new ConcurrentHashMap<>();
        final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        volatile ServerSocket server;

        Listener(InetSocketAddress bind, String admission, String spawn, Consumer<IOException> startupFailure) {
            endpoint.onStart(() -> {
                try {
                    var opened = new ServerSocket();
                    server = opened;
                    opened.bind(bind);
                    workers.submit(() -> accept(opened, admission, spawn));
                } catch (IOException failure) {
                    stop();
                    startupFailure.accept(failure);
                }
            });
            endpoint.onStop(this::stop);
            endpoint.onCloseClient(id -> {
                var peer = active.get(id);
                if (peer != null) peer.close();
            });
            endpoint.onDispatchPacket((id, header, payload, delivery) -> {
                var peer = active.get(id);
                if (peer == null || header.readInstruction() != ClientInstruction.INSTRUCTION_SIGNAL) {
                    return ClientDispatchResult.FAILED;
                }
                // Client V1 Signal fields precede the original payload, in Bitsery little-endian order.
                var signal = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
                if (signal.remaining() < 12) {
                    return ClientDispatchResult.FAILED;
                }
                int kind = signal.getInt();
                signal.getInt(); // AtlasNet signal instance number
                if (signal.getInt() != signal.remaining() || !signal.hasRemaining()) {
                    return ClientDispatchResult.FAILED;
                }
                if (kind == MinecraftIngressPackets.PLAYER_ACTIVITY_SIGNAL) {
                    if (signal.remaining() <= Long.BYTES) return ClientDispatchResult.FAILED;
                    if (signal.getLong() == id) return ClientDispatchResult.SENT;
                } else if (kind != PLAY_PACKET_SIGNAL) {
                    return ClientDispatchResult.FAILED;
                }
                return peer.offer(new Output(false, Arrays.copyOfRange(payload, signal.position(), payload.length)));
            });
        }

        void accept(ServerSocket opened, String admission, String spawn) {
            while (!opened.isClosed()) {
                try {
                    var socket = opened.accept();
                    if (opened.isClosed()) {
                        socket.close();
                        return;
                    }
                    var peer = new Peer(this, socket, admission, spawn);
                    peers.add(peer);
                    if (opened.isClosed()) {
                        peer.close();
                        return;
                    }
                    workers.submit(peer::write);
                    workers.submit(peer::read);
                } catch (IOException | java.util.concurrent.RejectedExecutionException failure) {
                    if (!opened.isClosed()) LOG.log(System.Logger.Level.WARNING, "Minecraft listener stopped", failure);
                    return;
                }
            }
        }

        void stop() {
            if (server != null) {
                try { server.close(); } catch (IOException ignored) {}
            }
            peers.forEach(Peer::close);
            workers.shutdownNow();
            workers.close();
        }
    }

    private record Output(boolean admission, byte[] bytes) {}

    private static final class Peer {
        final Listener owner;
        final Socket socket;
        final String admission, spawn;
        final MinecraftTcpCodec codec = new MinecraftTcpCodec();
        final ArrayBlockingQueue<Output> outgoing = new ArrayBlockingQueue<>(1024);
        final CompletableFuture<Void> encryption = new CompletableFuture<>();
        final CompletableFuture<Long> admitted = new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        final ByteBuffer input = ByteBuffer.allocate(16 * 1024).limit(0);
        volatile ClientAdmissionTicket ticket;
        volatile Thread writer;
        volatile long clientId;
        int pendingBytes;

        Peer(Listener owner, Socket socket, String admission, String spawn) {
            this.owner = owner;
            this.socket = socket;
            this.admission = admission;
            this.spawn = spawn;
        }

        synchronized ClientDispatchResult offer(Output output) {
            if (closed.get()) return ClientDispatchResult.FAILED;
            if (output.bytes().length > MAX_PENDING_BYTES - pendingBytes || !outgoing.offer(output)) {
                return ClientDispatchResult.BACKPRESSURE;
            }
            pendingBytes += output.bytes().length;
            return ClientDispatchResult.SENT;
        }

        void read() {
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(30_000);
                var handshake = ByteBuffer.wrap(packet());
                if (varInt(handshake) != 0 || varInt(handshake) != PROTOCOL) return;
                int hostLength = varInt(handshake);
                if (hostLength < 1 || hostLength > 1020 || hostLength > handshake.remaining() - 3) return;
                handshake.position(handshake.position() + hostLength);
                handshake.getShort(); // requested server port
                if (varInt(handshake) != 2 || handshake.hasRemaining()) return; // Login intention

                byte[] hello = packet();
                if (ByteBuffer.wrap(hello).get() != 0) return;
                var peer = (InetSocketAddress) socket.getRemoteSocketAddress();
                var request = ByteBuffer.allocate(3 + peer.getAddress().getAddress().length + hello.length);
                MinecraftIngressPackets.writePeer(request, peer).put(hello);
                ticket = owner.endpoint.connectInteractive(peer.toString(),
                        admission, spawn, 1, request.array(),
                        message -> offer(new Output(true, message)),
                        this::close, this::complete);
                if (ticket == null) return;
                if (closed.get()) {
                    ticket.cancel();
                    return;
                }

                boolean configuration = false;
                for (;;) {
                    byte[] body = packet();
                    var fields = ByteBuffer.wrap(body);
                    int id = varInt(fields);
                    if (ticket.submit(body) != ClientPacketSubmission.QUEUED) return;
                    if (!configuration && id == 1) {
                        // Stop parsing at the key reply until the ordered cipher transition is applied.
                        encryption.join();
                    } else if (!configuration && id == 3 && !fields.hasRemaining()) {
                        configuration = true; // Login Acknowledged
                    } else if (configuration && id == 3 && !fields.hasRemaining()) {
                        long client = admitted.join(); // Finish Configuration
                        socket.setSoTimeout(0);
                        play(client);
                        return;
                    }
                }
            } catch (IOException | GErrorException | RuntimeException failure) {
                if (!closed.get()) LOG.log(System.Logger.Level.DEBUG, "Minecraft connection ended", failure);
            } finally {
                close();
            }
        }

        private void play(long client) throws IOException, GErrorException {
            var header = new PacketHeader();
            header.writeMagic(ATLASNET_V1_MAGIC);
            header.writeVersion(ClientHeaderVersion.HEADER_V1);
            header.writeChannelId(1);
            header.writeInstruction(ClientInstruction.INSTRUCTION_COMMAND);
            while (!closed.get()) {
                byte[] body = packet();
                header.writePayloadSize(body.length);
                if (owner.endpoint.submitPacket(client, header, body) != ClientPacketSubmission.QUEUED) return;
            }
        }

        void complete(ClientConnectStatus status, long id, String reason, byte[] response) {
            if (status != ClientConnectStatus.ACCEPTED) {
                // Core issues the bounded forced-close callback after the final response has had time to flush.
                if (response.length != 0) offer(new Output(true, response));
                if (status == ClientConnectStatus.CANCELLED) close();
                return;
            }
            clientId = id;
            owner.active.put(id, this);
            if (closed.get()) {
                if (owner.active.remove(id, this)) disconnect(id);
            } else {
                admitted.complete(id);
            }
        }

        void write() {
            writer = Thread.currentThread();
            try {
                var output = socket.getOutputStream();
                while (!closed.get()) {
                    var next = outgoing.take();
                    synchronized (this) { pendingBytes -= next.bytes().length; }
                    synchronized (codec) {
                        if (next.admission()) {
                            codec.applyAdmissionMessage(next.bytes(), output::write);
                            if (next.bytes()[0] == 1) encryption.complete(null);
                        } else {
                            codec.write(next.bytes(), output::write);
                        }
                    }
                }
            } catch (IOException | RuntimeException failure) {
                if (!closed.get()) LOG.log(System.Logger.Level.DEBUG, "Minecraft write failed", failure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                close();
            }
        }

        byte[] packet() throws IOException {
            for (;;) {
                synchronized (codec) {
                    byte[] packet = codec.read(input);
                    if (packet != null) return packet;
                }
                int read = socket.getInputStream().read(input.array());
                if (read < 0) throw new EOFException();
                input.position(0).limit(read);
            }
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { socket.close(); } catch (IOException ignored) {}
            var error = new EOFException("Minecraft connection closed");
            encryption.completeExceptionally(error);
            admitted.completeExceptionally(error);
            if (writer != null && writer != Thread.currentThread()) writer.interrupt();
            long id = clientId;
            if (id != 0) {
                if (owner.active.remove(id, this)) disconnect(id);
            } else if (ticket != null) {
                ticket.cancel();
            }
            owner.peers.remove(this);
        }

        private void disconnect(long id) {
            try { owner.endpoint.disconnectClient(id); }
            catch (GErrorException failure) { LOG.log(System.Logger.Level.DEBUG, "AtlasNet disconnect failed", failure); }
        }
    }

    private static int varInt(ByteBuffer buffer) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 32 && buffer.hasRemaining(); shift += 7) {
            int value = buffer.get() & 0xff;
            if (shift == 28 && (value & 0xf0) != 0) break;
            result |= (value & 0x7f) << shift;
            if ((value & 0x80) == 0) return result;
        }
        throw new IOException("Invalid Minecraft VarInt");
    }
}
