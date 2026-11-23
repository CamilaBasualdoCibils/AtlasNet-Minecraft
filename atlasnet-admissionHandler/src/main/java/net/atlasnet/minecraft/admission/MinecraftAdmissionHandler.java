package net.atlasnet.minecraft.admission;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.atlasnet.minecraft.sdk.core.MinecraftIngressPackets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.config.SynchronizeRegistriesTask;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import net.minecraft.util.StringUtil;
import org.atlasnet.atlasnet.AdmissionResult;
import org.atlasnet.atlasnet.ClientAdmissionSession;
import org.atlasnet.atlasnet.ClientPacketSubmission;
import org.atlasnet.atlasnet.InteractiveAdmissionHandler;
import org.atlasnet.atlasnet.Registry;
import org.javagi.base.GErrorException;

/** Registers the shard-independent, online-mode Minecraft admission handler. */
public final class MinecraftAdmissionHandler implements AutoCloseable {
    private static final String SERVER_ID = "";
    private static final int PACKET = 0;
    private static final int ENCRYPTION = 1;
    private static final int COMPRESSION = 2;
    private final MinecraftAdmissionConfig config;
    private final java.util.function.Function<java.util.UUID, byte[]> playerCheckpoint;
    private final VanillaConfiguration vanilla;
    private final MinecraftSessionService sessionService;
    private final KeyPair keys;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService authentication = Executors.newVirtualThreadPerTaskExecutor();
    // Registry callbacks are serialized on the ingress thread. Authentication workers use only Session.
    private final Map<Long, Session> sessions = new HashMap<>();

    public MinecraftAdmissionHandler(MinecraftAdmissionConfig config) throws CryptException {
        this(config, uuid -> new byte[0]);
    }

    public MinecraftAdmissionHandler(MinecraftAdmissionConfig config,
            java.util.function.Function<java.util.UUID, byte[]> playerCheckpoint) throws CryptException {
        this(config, new YggdrasilAuthenticationService(Proxy.NO_PROXY).createMinecraftSessionService(),
                playerCheckpoint);
    }

    MinecraftAdmissionHandler(MinecraftAdmissionConfig config, MinecraftSessionService sessionService)
            throws CryptException {
        this(config, sessionService, uuid -> new byte[0]);
    }

    private MinecraftAdmissionHandler(MinecraftAdmissionConfig config, MinecraftSessionService sessionService,
            java.util.function.Function<java.util.UUID, byte[]> playerCheckpoint) throws CryptException {
        this.playerCheckpoint = playerCheckpoint;
        this.config = config;
        this.sessionService = sessionService;
        this.keys = Crypt.generateKeyPair();
        this.vanilla = VanillaConfiguration.load(config);
    }

    public void register(Registry registry, String name) throws GErrorException {
        var handler = new InteractiveAdmissionHandler();
        handler.onStart((session, header, payload) -> {
            var connection = new Session(session.copy());
            sessions.put(session.getClientId(), connection);
            connection.start(payload);
        });
        handler.onPacket((session, payload) -> {
            var connection = sessions.get(session.getClientId());
            if (connection != null) {
                connection.receive(payload);
            }
        });
        handler.onClosed(id -> {
            var connection = sessions.remove(id);
            if (connection != null) {
                connection.closed();
            }
        });
        registry.registerInteractiveAdmissionHandler(name, handler);
    }

    @Override
    public void close() {
        authentication.shutdownNow();
    }

    private enum Phase {
        LOGIN_START, ENCRYPTION_RESPONSE, VERIFYING, LOGIN_ACK, KNOWN_PACKS, CONFIGURATION_ACK, DONE
    }

    private final class Session {
        private final ClientAdmissionSession atlas;
        private final byte[] challenge = new byte[4];
        private Phase phase = Phase.LOGIN_START;
        private InetSocketAddress peer;
        private String username;
        private GameProfile profile;
        private ClientInformation clientInfo = ClientInformation.createDefault();
        private SynchronizeRegistriesTask registryTask;
        private Future<?> verification;

        Session(ClientAdmissionSession atlas) {
            this.atlas = atlas;
            random.nextBytes(challenge);
        }

        synchronized void start(byte[] payload) {
            try {
                var request = ByteBuffer.wrap(payload);
                peer = MinecraftIngressPackets.readPeer(request);
                var hello = new byte[request.remaining()];
                request.get(hello);
                receive(hello);
            } catch (RuntimeException failure) {
                reject("Invalid Minecraft ingress metadata", "Invalid admission data");
            }
        }

        synchronized void receive(byte[] payload) {
            if (phase == Phase.DONE) {
                return;
            }
            try {
                var packet = decode(isConfiguration() ? ConfigurationProtocols.SERVERBOUND : LoginProtocols.SERVERBOUND,
                        payload);
                if (phase == Phase.LOGIN_START && packet instanceof ServerboundHelloPacket hello) {
                    startLogin(hello);
                } else if (phase == Phase.ENCRYPTION_RESPONSE && packet instanceof ServerboundKeyPacket key) {
                    verifyLogin(key);
                } else if (phase == Phase.LOGIN_ACK && packet instanceof ServerboundLoginAcknowledgedPacket) {
                    startConfiguration();
                } else if (isConfiguration() && packet instanceof ServerboundClientInformationPacket information) {
                    clientInfo = information.information();
                } else if (isConfiguration() && packet instanceof ServerboundCustomPayloadPacket) {
                    // Vanilla sends its brand in this phase. It is not identity or admission data.
                } else if (phase == Phase.KNOWN_PACKS && packet instanceof ServerboundSelectKnownPacks packs) {
                    registryTask.handleResponse(packs.knownPacks(), this::configurationPacket);
                    configurationPacket(ClientboundFinishConfigurationPacket.INSTANCE);
                    phase = Phase.CONFIGURATION_ACK;
                } else if (phase == Phase.CONFIGURATION_ACK &&
                        packet instanceof ServerboundFinishConfigurationPacket) {
                    accept();
                } else {
                    reject("Unexpected packet during " + phase, "Unexpected admission packet");
                }
            } catch (CryptException | RuntimeException failure) {
                reject("Failed Minecraft admission in " + phase, "Invalid admission data");
            }
        }

        private void startLogin(ServerboundHelloPacket hello) {
            if (!StringUtil.isValidPlayerName(hello.name())) {
                reject("Invalid Minecraft username", "Invalid username");
                return;
            }
            username = hello.name();
            send(message(PACKET, encode(LoginProtocols.CLIENTBOUND,
                    new ClientboundHelloPacket(SERVER_ID, keys.getPublic().getEncoded(), challenge, true))));
            phase = Phase.ENCRYPTION_RESPONSE;
        }

        private void verifyLogin(ServerboundKeyPacket packet) throws CryptException {
            if (!packet.isChallengeValid(challenge, keys.getPrivate())) {
                reject("Minecraft encryption challenge did not match", "Invalid encryption response");
                return;
            }
            var secret = packet.getSecretKey(keys.getPrivate());
            if (secret.getEncoded().length != 16) {
                reject("Invalid Minecraft AES key length", "Invalid encryption response");
                return;
            }
            var hash = new BigInteger(Crypt.digestData(SERVER_ID, keys.getPublic(), secret)).toString(16);
            send(message(ENCRYPTION, secret.getEncoded()));
            phase = Phase.VERIFYING;
            verification = authentication.submit(() -> authenticate(hash));
        }

        private byte[] checkpoint = new byte[0];

        private void authenticate(String hash) {
            try {
                var result = sessionService.hasJoinedServer(username, hash, null);
                byte[] restored = result == null || result.profile().getId() == null
                        ? new byte[0] : playerCheckpoint.apply(result.profile().getId());
                if (restored.length > MinecraftIngressPackets.MAX_PLAYER_CHECKPOINT_BYTES)
                    throw new IllegalArgumentException("Player checkpoint exceeds admission limit");
                synchronized (this) {
                    if (phase != Phase.VERIFYING) {
                        return;
                    }
                    if (result == null || result.profile().getId() == null) {
                        reject("Minecraft session verification declined", "Failed to verify username");
                        return;
                    }
                    profile = result.profile();
                    checkpoint = restored;
                    if (config.compressionThreshold() >= 0) {
                        var packet = encode(LoginProtocols.CLIENTBOUND,
                                new ClientboundLoginCompressionPacket(config.compressionThreshold()));
                        var buffer = new FriendlyByteBuf(Unpooled.buffer());
                        try {
                            buffer.writeVarInt(config.compressionThreshold());
                            buffer.writeBytes(packet);
                            send(message(COMPRESSION, bytes(buffer)));
                        } finally {
                            buffer.release();
                        }
                    }
                    send(message(PACKET, encode(LoginProtocols.CLIENTBOUND,
                            new ClientboundGameProfilePacket(profile, true))));
                    phase = Phase.LOGIN_ACK;
                }
            } catch (AuthenticationUnavailableException | RuntimeException failure) {
                synchronized (this) {
                    if (phase == Phase.VERIFYING) {
                        reject("Minecraft session verification failed", "Authentication unavailable");
                    }
                }
            }
        }

        private boolean isConfiguration() {
            return phase == Phase.KNOWN_PACKS || phase == Phase.CONFIGURATION_ACK;
        }

        private void startConfiguration() {
            phase = Phase.KNOWN_PACKS;
            configurationPacket(new ClientboundCustomPayloadPacket(new BrandPayload(config.brand())));
            configurationPacket(new ClientboundUpdateEnabledFeaturesPacket(vanilla.features()));
            registryTask = vanilla.newRegistryTask();
            registryTask.start(this::configurationPacket);
        }

        private void configurationPacket(Packet<?> packet) {
            send(message(PACKET, encode(ConfigurationProtocols.CLIENTBOUND, packet)));
        }

        private void accept() {
            var payload = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ByteBufCodecs.GAME_PROFILE.encode(payload, profile);
                clientInfo.write(payload);
                var metadata = ByteBuffer.allocate(3 + peer.getAddress().getAddress().length);
                MinecraftIngressPackets.writePeer(metadata, peer);
                payload.writeBytes(metadata.array());
                payload.writeBytes(checkpoint);
                var result = new AdmissionResult();
                result.setData(bytes(payload));
                phase = Phase.DONE;
                complete(result);
            } finally {
                payload.release();
            }
        }

        private void reject(String reason, String message) {
            if (phase == Phase.DONE) {
                return;
            }
            boolean configuration = isConfiguration();
            phase = Phase.DONE;
            var result = new AdmissionResult();
            result.writeAccepted(false);
            result.setRejectionReason(reason);
            result.setResponsePayload(message(PACKET, configuration
                    ? encode(ConfigurationProtocols.CLIENTBOUND, new ClientboundDisconnectPacket(Component.literal(message)))
                    : encode(LoginProtocols.CLIENTBOUND, new ClientboundLoginDisconnectPacket(Component.literal(message)))));
            complete(result);
        }

        private void send(byte[] message) {
            try {
                if (atlas.send(message) != ClientPacketSubmission.QUEUED) {
                    throw new IllegalStateException("Could not queue Minecraft admission message");
                }
            } catch (GErrorException failure) {
                throw new IllegalStateException("AtlasNet admission send failed", failure);
            }
        }

        private void complete(AdmissionResult result) {
            try {
                atlas.complete(result);
            } catch (GErrorException failure) {
                throw new IllegalStateException("AtlasNet admission completion failed", failure);
            }
        }

        synchronized void closed() {
            phase = Phase.DONE;
            if (verification != null) {
                verification.cancel(true);
            }
        }
    }

    private static Packet<?> decode(ProtocolInfo<?> protocol, byte[] payload) {
        var buffer = Unpooled.wrappedBuffer(payload);
        try {
            var packet = protocol.codec().decode(buffer);
            if (buffer.isReadable()) {
                throw new IllegalArgumentException("Trailing bytes in Minecraft admission packet");
            }
            return packet;
        } finally {
            buffer.release();
        }
    }

    @SuppressWarnings("unchecked") // Vanilla's registry task emits Packet<?> values for the Configuration codec.
    private static <T extends net.minecraft.network.PacketListener> byte[] encode(ProtocolInfo<T> protocol,
                                                                                   Packet<?> packet) {
        var buffer = Unpooled.buffer();
        try {
            protocol.codec().encode(buffer, (Packet<? super T>) packet);
            return bytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        var result = new byte[buffer.readableBytes()];
        buffer.readBytes(result);
        return result;
    }

    private static byte[] message(int kind, byte[] payload) {
        var message = new byte[payload.length + 1];
        message[0] = (byte) kind;
        System.arraycopy(payload, 0, message, 1, payload.length);
        return message;
    }
}
