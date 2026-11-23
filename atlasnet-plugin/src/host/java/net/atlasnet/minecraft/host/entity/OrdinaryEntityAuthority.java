package net.atlasnet.minecraft.host.entity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import io.netty.buffer.Unpooled;
import net.atlasnet.minecraft.host.ChunkTerrainAuthority;
import net.atlasnet.minecraft.sdk.core.MinecraftIngressPackets;
import net.atlasnet.minecraft.server.MinecraftEntityLifecycle;
import net.atlasnet.minecraft.server.MinecraftShardBridge;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.ChunkEntities;
import org.atlasnet.atlasnet.Shard;
import org.atlasnet.atlasnet.StateStoreValues;
import org.atlasnet.atlasnet.StateStoreWriteCondition;
import org.atlasnet.atlasnet.Transform;
import org.gnome.glib.Variant;
import org.javagi.base.GErrorException;

/** Bridges vanilla tracking events to AtlasNet entity registration. */
public final class OrdinaryEntityAuthority implements MinecraftEntityLifecycle.Observer {
    private static final System.Logger LOG = System.getLogger(OrdinaryEntityAuthority.class.getName());

    private static final TicketType<UUID> TRANSFER_TICKET =
            TicketType.create("atlasnet_entity_transfer", UUID::compareTo);

    private static final class Entry {
        final String key;
        final UUID uuid;
        String token = UUID.randomUUID().toString();
        Runnable startTicking, stopTicking;
        final double x, y, z;
        net.minecraft.world.phys.Vec3 lastPosition;
        volatile boolean retired;
        volatile long id;
        volatile boolean claimed;
        volatile boolean shutdownRetiring;
        boolean active;
        boolean publishingInitialState;

        Entry(Entity entity) {
            uuid = entity.getUUID();
            key = "vanilla-uuid/" + uuid;
            x = entity.getX();
            y = entity.getY();
            z = entity.getZ();
            lastPosition = entity.position();
        }
    }

    private record Outgoing(Entity entity, Entry entry, ChunkPos chunk) {
        void releaseChunk() {
            var level = (ServerLevel) entity.level();
            level.getChunkSource().removeRegionTicket(TRANSFER_TICKET, chunk, 2, entry.uuid);
        }
    }

    private static final class Incoming {
        final Entity entity;
        final Entry entry;
        final AtlasNetEntityStore.Snapshot snapshot;
        CompletableFuture<Boolean> publication;

        Incoming(Entity entity, Entry entry, AtlasNetEntityStore.Snapshot snapshot) {
            this.entity = entity;
            this.entry = entry;
            this.snapshot = snapshot;
        }
    }

    private record PlayerTicks(Runnable start, Runnable stop) {}
    private record OutgoingPlayer(ServerPlayer entity, MinecraftShardBridge.PlayerTransfer metadata,
                                  AtlasNetEntityStore.Snapshot snapshot) {}

    private static final class IncomingPlayer {
        final MinecraftShardBridge.PlayerTransfer metadata;
        final AtlasNetEntityStore.Snapshot snapshot;
        CompletableFuture<Boolean> publication;

        IncomingPlayer(MinecraftShardBridge.PlayerTransfer metadata, AtlasNetEntityStore.Snapshot snapshot) {
            this.metadata = metadata;
            this.snapshot = snapshot;
        }
    }

    private final IdentityHashMap<Entity, PlayerTicks> playerTicks = new IdentityHashMap<>();
    private final HashMap<Long, OutgoingPlayer> outgoingPlayers = new HashMap<>();
    private final HashMap<Long, IncomingPlayer> incomingPlayers = new HashMap<>();
    // Used on the Minecraft thread only.
    private final java.util.Set<Entity> frozenPlayers =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private final HashMap<Long, Outgoing> outgoing = new HashMap<>();
    private final HashMap<Long, Incoming> incoming = new HashMap<>();
    private final IdentityHashMap<Entity, Entry> activating = new IdentityHashMap<>();
    private final Shard shard;
    private final StateStoreValues identities;
    private final PlayerEntityPublication publication;
    private final IdentityHashMap<Entity, Entry> tracked = new IdentityHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> actions = new ConcurrentLinkedQueue<>();
    private final java.util.concurrent.ConcurrentHashMap<Entry, net.minecraft.world.phys.Vec3> transforms =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.HashSet<Entry> reservations = new java.util.HashSet<>();
    private boolean stopping;
    private int ticks;

    public OrdinaryEntityAuthority(Shard shard, PlayerEntityPublication publication) throws GErrorException {
        this.shard = shard;
        this.publication = publication;
        identities = shard.getStateStore("minecraft.entities").getValues();
        shard.onShutdown(() -> {
            stopping = true;
            actions.clear();
            transforms.clear();
        });
        shard.onPoll(() -> {
            for (int i = 0; i < 256; i++) {
                var action = actions.poll();
                if (action == null) break;
                action.run();
            }
            transforms.forEach((entry, position) -> {
                if (entry.retired) {
                    transforms.remove(entry, position);
                    return;
                }
                try {
                    shard.updateEntityTransform(entry.id, transform(position.x, position.y, position.z));
                    transforms.remove(entry, position);
                } catch (GErrorException failure) {
                    log(entry, failure);
                }
            });
        });
    }

    @Override public CompletableFuture<ChunkEntities<Entity>> restoreChunk(
            ServerLevel level, ChunkPos position, ChunkEntities<Entity> original) {
        return publication.loadCheckpointChunk(level.dimension().location().toString(), position)
                .thenCompose(snapshots -> {
                    if (snapshots.isEmpty()) return CompletableFuture.completedFuture(original);
                    return level.getServer().submit(() -> mergeCheckpoints(level, position, original, snapshots));
                }).exceptionally(failure -> {
                    LOG.log(System.Logger.Level.WARNING, "Could not restore checkpoint entities in " + position, failure);
                    return original;
                });
    }

    private ChunkEntities<Entity> mergeCheckpoints(ServerLevel level, ChunkPos position,
                                                   ChunkEntities<Entity> original,
                                                   java.util.List<AtlasNetEntityStore.Snapshot> snapshots) {
        var entities = new java.util.ArrayList<>(original.getEntities().toList());
        var known = new java.util.HashSet<UUID>();
        for (var entity : entities) {
            entity.getSelfAndPassengers().forEach(passenger -> known.add(passenger.getUUID()));
        }
        for (var snapshot : snapshots) {
            if (known.contains(snapshot.minecraftId()) || level.getEntity(snapshot.minecraftId()) != null) continue;
            var tag = snapshot.data();
            if (!tag.hasUUID("UUID") || !tag.getUUID("UUID").equals(snapshot.minecraftId())) continue;
            tag.putString("id", snapshot.entityType());
            EntityType.create(tag, level).ifPresent(entity -> {
                if (entity.chunkPosition().equals(position)) {
                    entities.add(entity);
                    known.add(entity.getUUID());
                }
            });
        }
        // Vanilla admits the merged entities normally. The existing StateStore UUID
        // reservation decides which overlapping worker is allowed to activate them.
        return new ChunkEntities<>(position, entities);
    }

    @Override public void trackingStarted(Entity entity, Runnable startTicking, Runnable stopTicking) {
        if (entity instanceof Player) {
            playerTicks.put(entity, new PlayerTicks(startTicking, stopTicking));
            return;
        }
        var transferred = activating.remove(entity);
        var entry = transferred == null ? new Entry(entity) : transferred;
        entry.startTicking = startTicking;
        entry.stopTicking = stopTicking;
        tracked.put(entity, entry);
        if (transferred != null) return;
        var server = entity.getServer();
        if (server == null) throw new IllegalStateException("Tracked entity has no server");
        actions.add(() -> reserve(entry, () -> restoreTracked(entity, entry, server)));
    }

    private void restoreTracked(Entity entity, Entry entry, MinecraftServer server) {
        if (entry.retired) return;
        publication.loadCheckpoint(entry.uuid).whenComplete((checkpoint, failure) -> server.execute(() -> {
            if (tracked.get(entity) != entry || entry.retired) return;
            if (failure != null) {
                log(entry, failure);
                CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS)
                        .execute(() -> restoreTracked(entity, entry, server));
                return;
            }
            if (checkpoint.filter(PostgresEntityCheckpoints.Checkpoint::deleted).isPresent()) {
                entity.discard();
                return;
            }
            var snapshot = checkpoint.flatMap(PostgresEntityCheckpoints.Checkpoint::snapshot);
            if (snapshot.isPresent()) {
                var data = snapshot.get();
                if (!data.entityType().equals(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(entity.getType()).toString()) ||
                        !data.dimension().equals(entity.level().dimension().location().toString())) {
                    log(entry, new IllegalStateException("Checkpoint type or dimension disagrees with vanilla entity"));
                    trackingEnded(entity);
                    return;
                }
                if (!data.data().hasUUID("UUID") || !entry.uuid.equals(data.data().getUUID("UUID"))) {
                    log(entry, new IllegalStateException("Checkpoint UUID disagrees with vanilla entity"));
                    trackingEnded(entity);
                    return;
                }
                try {
                    entity.load(data.data());
                } catch (RuntimeException loadFailure) {
                    log(entry, loadFailure);
                    trackingEnded(entity);
                    return;
                }
            }
            AtlasNetEntityStore.Snapshot initial;
            try {
                initial = AtlasNetEntityStore.Snapshot.capture(entry.id, 1, 1, entity);
            } catch (RuntimeException failureToCapture) {
                log(entry, failureToCapture);
                trackingEnded(entity);
                return;
            }
            entry.publishingInitialState = true;
            actions.add(() -> publication.initialize(initial).thenAccept(published -> server.execute(() -> {
                if (tracked.get(entity) != entry || entry.retired) return;
                if (!published) {
                    trackingEnded(entity);
                    return;
                }
                entry.active = true;
                entry.startTicking.run();
            })));
        }));
    }

    /** Bind AtlasNet's existing callbacks on the worker. Staged entities stay outside the live level. */
    public void registerTransfers(MinecraftServer server, MinecraftShardBridge bridge) {
        registerTransfers(server, bridge, null);
    }

    public void registerTransfers(MinecraftServer server, MinecraftShardBridge bridge,
                                  ChunkTerrainAuthority terrain) {
        shard.onPrepareExtract((id, transfer) -> {
            if (terrain != null && terrain.owns(id)) return terrain.prepareExtract(id);
            var player = preparePlayerExtract(server, bridge, id);
            return player == null ? prepareExtract(server, id) : player;
        });
        shard.onPrepareRestore((id, transfer, data) -> {
            if (terrain != null) {
                var prepared = terrain.prepareRestore(id, data);
                if (prepared.isPresent()) return prepared.get();
            }
            return prepareRestore(server, bridge, id, data);
        });
        shard.onCommitExtract((id, transfer) -> {
            if (terrain != null && terrain.owns(id)) return terrain.commitExtract(id);
            var player = outgoingPlayers.get(id);
            if (player != null) {
                server.submit(() -> {
                    bridge.detachPlayer(id);
                    frozenPlayers.remove(player.entity());
                }).join();
                publication.commitFreeze(id);
                outgoingPlayers.remove(id);
                return true;
            }
            var source = outgoing.get(id);
            if (source == null) return false;
            server.submit(() -> {
                tracked.remove(source.entity());
                source.entry().retired = true;
                try {
                    source.entity().remove(Entity.RemovalReason.CHANGED_DIMENSION);
                } finally {
                    source.releaseChunk();
                }
            }).join();
            // The vanilla UUID reservation passes to the destination with the existing EntityID.
            reservations.remove(source.entry());
            publication.commitFreeze(id);
            outgoing.remove(id);
            return true;
        });
        shard.onCommitRestore((id, transfer) -> {
            if (terrain != null && terrain.hasIncoming(id)) return terrain.commitRestore(id);
            return incomingPlayers.containsKey(id) ? commitPlayerRestore(server, bridge, id) : commitRestore(server, id);
        });
        shard.onAbortExtract((id, transfer) -> {
            if (terrain != null && terrain.owns(id)) {
                terrain.abortExtract(id);
                return;
            }
            unfreezePlayer(server, bridge, id);
            unfreeze(server, id);
        });
        shard.onAbortRestore((id, transfer) -> {
            if (terrain != null) terrain.abortRestore(id);
            incomingPlayers.remove(id);
            incoming.remove(id);
        });
    }

    private byte[] preparePlayerExtract(MinecraftServer server, MinecraftShardBridge bridge, long id) {
        try {
            var state = server.submit(() -> {
                var player = bridge.localPlayer(id);
                if (player == null || !playerTicks.containsKey(player)) return null;
                var snapshot = AtlasNetEntityStore.Snapshot.capture(id, 1, 1, player);
                var metadata = bridge.freezePlayer(id);
                if (metadata == null) return null;
                frozenPlayers.add(player);
                playerTicks.get(player).stop().run();
                return new OutgoingPlayer(player, metadata, snapshot);
            }).join();
            if (state == null) return null;
            outgoingPlayers.put(id, state);
            var snapshot = publication.freeze(id, state.snapshot());
            if (snapshot != null) {
                var metadata = state.metadata();
                var bytes = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    bytes.writeLong(metadata.client());
                    bytes.writeInt(metadata.playId());
                    ByteBufCodecs.GAME_PROFILE.encode(bytes, metadata.profile());
                    metadata.information().write(bytes);
                    var peer = ByteBuffer.allocate(19);
                    MinecraftIngressPackets.writePeer(peer, metadata.peer()).flip();
                    bytes.writeBytes(peer);
                    bytes.writeUtf(metadata.observer().interest());
                    bytes.writeVarInt(metadata.observer().visiblePlayers().size());
                    metadata.observer().visiblePlayers().forEach((entity, uuid) ->
                            bytes.writeLong(entity).writeUUID(uuid));
                    var result = new ByteArrayOutputStream();
                    try (var output = new DataOutputStream(result)) {
                        output.writeUTF("");
                        output.writeInt(bytes.readableBytes());
                        bytes.readBytes(output, bytes.readableBytes());
                        output.write(AtlasNetEntityStore.encode(snapshot));
                    }
                    return result.toByteArray();
                } finally {
                    bytes.release();
                }
            }
        } catch (IOException | RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Cannot prepare Minecraft player extraction", failure);
        }
        unfreezePlayer(server, bridge, id);
        return null;
    }

    private void unfreezePlayer(MinecraftServer server, MinecraftShardBridge bridge, long id) {
        var source = outgoingPlayers.remove(id);
        if (source == null) return;
        publication.abortFreeze(id);
        server.submit(() -> {
            bridge.resumePlayer(id);
            frozenPlayers.remove(source.entity());
            var ticks = playerTicks.get(source.entity());
            if (ticks != null) ticks.start().run();
        }).join();
    }

    private boolean preparePlayerRestore(MinecraftServer server, MinecraftShardBridge bridge, long id,
                                         DataInputStream input) throws IOException {
        int metadataSize = input.readInt();
        if (metadataSize <= 0 || metadataSize > 65536 || metadataSize >= input.available()) return false;
        var bytes = new FriendlyByteBuf(Unpooled.wrappedBuffer(input.readNBytes(metadataSize)));
        MinecraftShardBridge.PlayerTransfer metadata;
        try {
            long client = bytes.readLong();
            int playId = bytes.readInt();
            var profile = ByteBufCodecs.GAME_PROFILE.decode(bytes);
            var information = new ClientInformation(bytes);
            var peer = bytes.nioBuffer();
            InetSocketAddress address = MinecraftIngressPackets.readPeer(peer);
            bytes.skipBytes(peer.position());
            if (profile.getId() == null || playId >= -1) return false;
            var observer = MinecraftShardBridge.ObserverState.EMPTY;
            if (bytes.isReadable()) {
                String interest = bytes.readUtf();
                int count = bytes.readVarInt();
                if (count < 0 || count > bytes.readableBytes() / 24 || bytes.readableBytes() != count * 24)
                    return false;
                var avatars = new java.util.HashMap<Long, UUID>();
                for (int i = 0; i < count; i++) {
                    if (avatars.put(bytes.readLong(), bytes.readUUID()) != null) return false;
                }
                observer = new MinecraftShardBridge.ObserverState(interest, avatars);
            }
            metadata = new MinecraftShardBridge.PlayerTransfer(client, playId, profile, information, address, observer);
        } finally {
            bytes.release();
        }
        var snapshot = AtlasNetEntityStore.decode(id, input.readAllBytes());
        var data = snapshot.data();
        if (!"minecraft:player".equals(snapshot.entityType()) ||
                !metadata.profile().getId().equals(snapshot.minecraftId()) ||
                !data.hasUUID("UUID") || !snapshot.minecraftId().equals(data.getUUID("UUID"))) return false;
        if (!server.submit(() -> server.getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(snapshot.dimension()))) != null &&
                bridge.canRestorePlayer(id, metadata)).join()) return false;
        incomingPlayers.put(id, new IncomingPlayer(metadata, snapshot));
        return true;
    }

    private boolean commitPlayerRestore(MinecraftServer server, MinecraftShardBridge bridge, long id) {
        var player = incomingPlayers.get(id);
        if (player.publication == null) player.publication = publication.adopt(player.snapshot);
        if (!player.publication.isDone()) return false;
        try {
            if (!player.publication.join()) {
                player.publication = null;
                return false;
            }
            if (!server.submit(() -> bridge.restorePlayer(id, player.metadata, player.snapshot.data())).join())
                return false;
            incomingPlayers.remove(id);
            return true;
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Cannot commit Minecraft player restoration", failure);
            if (player.publication.isCompletedExceptionally()) player.publication = null;
            return false;
        }
    }

    private byte[] prepareExtract(MinecraftServer server, long id) {
        try {
            var state = server.submit(() -> {
                var entity = findLocal(id);
                if (entity == null || entity.isPassenger() || entity.isVehicle()) return null;
                var entry = tracked.get(entity);
                var snapshot = AtlasNetEntityStore.Snapshot.capture(id, 1, 1, entity);
                var level = (ServerLevel) entity.level();
                var chunk = entity.chunkPosition();
                level.getChunkSource().addRegionTicket(TRANSFER_TICKET, chunk, 2, entry.uuid);
                entry.active = false;
                entry.stopTicking.run();
                return java.util.Map.entry(new Outgoing(entity, entry, chunk), snapshot);
            }).join();
            if (state == null) return null;
            outgoing.put(id, state.getKey());
            var snapshot = publication.freeze(id, state.getValue());
            if (snapshot != null) {
                var bytes = new ByteArrayOutputStream();
                try (var output = new DataOutputStream(bytes)) {
                    output.writeUTF(state.getKey().entry().token);
                    output.write(AtlasNetEntityStore.encode(snapshot));
                }
                return bytes.toByteArray();
            }
        } catch (IOException | RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Cannot prepare Minecraft entity extraction", failure);
        }
        unfreeze(server, id);
        return null;
    }

    private void unfreeze(MinecraftServer server, long id) {
        var source = outgoing.remove(id);
        if (source == null) return;
        publication.abortFreeze(id);
        server.submit(() -> {
            try {
                if (tracked.get(source.entity()) != source.entry() || source.entry().retired) return;
                source.entry().active = true;
                source.entry().startTicking.run();
            } finally {
                source.releaseChunk();
            }
        }).join();
    }

    private boolean prepareRestore(MinecraftServer server, MinecraftShardBridge bridge, long id, byte[] data) {
        if (data == null) return false;
        try (var input = new DataInputStream(new ByteArrayInputStream(data))) {
            var identity = input.readUTF();
            if (identity.isEmpty()) return preparePlayerRestore(server, bridge, id, input);
            var token = UUID.fromString(identity).toString();
            var snapshot = AtlasNetEntityStore.decode(id, input.readAllBytes());
            var target = server.submit(() -> {
                var level = server.getLevel(ResourceKey.create(Registries.DIMENSION,
                        ResourceLocation.parse(snapshot.dimension())));
                if (level == null || "minecraft:player".equals(snapshot.entityType())) return null;
                var tag = snapshot.data();
                if (!tag.hasUUID("UUID") || !snapshot.minecraftId().equals(tag.getUUID("UUID"))) return null;
                tag.putString("id", snapshot.entityType());
                var entity = EntityType.create(tag, level).orElse(null);
                if (entity == null || entity instanceof Player) return null;
                var entry = new Entry(entity);
                entry.id = id;
                entry.token = token;
                return new Incoming(entity, entry, snapshot);
            }).join();
            if (target == null) return false;
            incoming.put(id, target);
            return true;
        } catch (IOException | RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Cannot prepare Minecraft entity restoration", failure);
            return false;
        }
    }

    private boolean commitRestore(MinecraftServer server, long id) {
        var target = incoming.get(id);
        if (target == null) return false;
        if (target.publication == null) target.publication = publication.adopt(target.snapshot);
        // AtlasNet retries commits. Returning here lets the worker poll receive the StateStore result.
        if (!target.publication.isDone()) return false;
        try {
            if (!target.publication.join()) {
                target.publication = null;
                return false;
            }
            boolean activated = server.submit(() -> {
                var level = (ServerLevel) target.entity.level();
                var duplicate = level.getEntity(target.entry.uuid);
                if (duplicate != null) {
                    var entry = tracked.get(duplicate);
                    if (entry == null || entry.active || entry.id != 0) return false;
                    duplicate.remove(Entity.RemovalReason.CHANGED_DIMENSION);
                }
                activating.put(target.entity, target.entry);
                try {
                    if (!level.addFreshEntity(target.entity)) {
                        activating.remove(target.entity);
                        return false;
                    }
                    target.entry.active = true;
                    if (target.entry.startTicking != null) target.entry.startTicking.run();
                    return true;
                } finally {
                    // Tracking may begin later when the section becomes visible.
                    if (target.entity.isRemoved()) activating.remove(target.entity);
                }
            }).join();
            if (!activated) return false;
            target.entry.claimed = true;
            reservations.add(target.entry);
            incoming.remove(id);
            return true;
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Cannot commit Minecraft entity restoration", failure);
            if (target.publication.isCompletedExceptionally()) target.publication = null;
            return false;
        }
    }

    private void reserve(Entry entry, Runnable activate) {
        if (entry.retired) return;
        identities.putWithOptionsAsync(entry.key, Variant.string(entry.token), 0,
                StateStoreWriteCondition.IF_ABSENT, null, result -> {
            try {
                if (!identities.putWithOptionsFinish(result)) return;
                entry.claimed = true;
                reservations.add(entry);
                if (!entry.retired && !stopping) {
                    entry.id = shard.registerEntity(transform(entry.x, entry.y, entry.z));
                    if (entry.id != 0) {
                        activate.run();
                        return;
                    }
                }
                release(entry);
            } catch (GErrorException failure) {
                log(entry, failure);
                release(entry);
            }
        });
    }

    @Override public boolean mayTick(Entity entity) {
        if (entity instanceof Player) return !frozenPlayers.contains(entity);
        var entry = tracked.get(entity);
        return entry != null && !entry.retired && entry.active;
    }

    @Override public void trackingEnded(Entity entity) {
        playerTicks.remove(entity);
        frozenPlayers.remove(entity);
        var entry = tracked.remove(entity);
        if (entry == null) return;
        entry.retired = true;
        transforms.remove(entry);
        if (entry.shutdownRetiring) return;
        if (entry.active) {
            publication.capture(entry.id, entity);
            var reason = entity.getRemovalReason();
            publication.retire(entry.id, reason != null && reason.shouldDestroy(), () -> unregister(entry));
        } else if (entry.publishingInitialState) {
            var reason = entity.getRemovalReason();
            boolean deleted = reason != null && reason.shouldDestroy();
            actions.add(() -> publication.retire(entry.id, deleted, () -> unregister(entry)));
        } else {
            actions.add(() -> unregister(entry));
        }
    }

    private void unregister(Entry entry) {
        try {
            if (entry.id != 0) shard.unregisterEntity(entry.id);
        } catch (GErrorException failure) {
            log(entry, failure);
            return;
        }
        release(entry);
    }

    @Override public void destroyed(Entity entity) { trackingEnded(entity); }

    @Override public void tick() {
        boolean capture = ++ticks % 20 == 0;
        tracked.forEach((entity, entry) -> {
            if (!entry.active || entry.retired) return;
            if (capture) publication.capture(entry.id, entity);
            var position = entity.position();
            if (!position.equals(entry.lastPosition)) {
                entry.lastPosition = position;
                transforms.put(entry, position);
            }
        });
    }

    public void finishShutdown() {
        for (var entry : java.util.Set.copyOf(reservations)) {
            if (!entry.shutdownRetiring) release(entry);
        }
    }

    @Override public void beforeShutdown() {
        tracked.forEach((entity, entry) -> {
            if (!entry.active || entry.retired) return;
            entry.shutdownRetiring = true;
            publication.capture(entry.id, entity);
            publication.retire(entry.id, false, () -> release(entry));
        });
    }

    public long localId(Entity entity) {
        var entry = tracked.get(entity);
        return entry != null && entry.active && !entry.retired ? entry.id : 0;
    }

    public Entity findLocal(long id) {
        for (var item : tracked.entrySet()) {
            if (item.getValue().id == id && item.getValue().active && !item.getValue().retired) return item.getKey();
        }
        return null;
    }

    private void release(Entry entry) {
        if (!entry.claimed) return;
        entry.claimed = false;
        reservations.remove(entry);
        identities.deleteWithOptionsAsync(entry.key, StateStoreWriteCondition.IF_MATCHES,
                Variant.string(entry.token), result -> {
                    try { identities.deleteWithOptionsFinish(result); }
                    catch (GErrorException failure) { log(entry, failure); }
                });
    }

    private static Transform transform(double x, double y, double z) {
        var transform = new Transform();
        transform.writeX(x);
        transform.writeY(y);
        transform.writeZ(z);
        return transform;
    }

    private static void log(Entry entry, Throwable failure) {
        LOG.log(System.Logger.Level.WARNING, "Could not update ordinary entity authority " + entry.key, failure);
    }
}
