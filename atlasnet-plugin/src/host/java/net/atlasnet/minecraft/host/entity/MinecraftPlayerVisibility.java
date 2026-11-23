package net.atlasnet.minecraft.host.entity;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import net.atlasnet.minecraft.server.MinecraftShardBridge;
import net.atlasnet.minecraft.server.MinecraftPlayerJoin;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.atlasnet.atlasnet.Shard;
import org.javagi.base.GErrorException;

/** Sends vanilla client-side player representations for AtlasNet interest members. */
public final class MinecraftPlayerVisibility {
    private static final System.Logger LOG = System.getLogger(MinecraftPlayerVisibility.class.getName());
    private static final int EXIT = 1;

    private static final class Member {
        volatile Vec3 position;
        volatile AtlasNetEntityStore.Snapshot snapshot;
        volatile boolean active = true;
        boolean loading;
    }

    private static final class Interest {
        String handle;
        final HashMap<Long, Member> members = new HashMap<>();
    }

    private final MinecraftServer server;
    private final MinecraftShardBridge bridge;
    private final Shard shard;
    private final AtlasNetEntityStore entities;
    private final int radius;
    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
    // Interests belong to the AtlasNet worker. Client representations belong to the game thread.
    private final HashMap<Long, Interest> interests = new HashMap<>();
    private final HashMap<Long, HashMap<Long, UUID>> visible = new HashMap<>();
    private final ConcurrentHashMap<Long, String> handles = new ConcurrentHashMap<>();

    public MinecraftPlayerVisibility(MinecraftServer server, MinecraftShardBridge bridge, Shard shard,
                                     AtlasNetEntityStore entities, int radius) {
        this.server = server;
        this.bridge = bridge;
        this.shard = shard;
        this.entities = entities;
        this.radius = radius;
        bridge.onPlayerAttachmentChanged(this::attachmentChanged);
        bridge.onObserverTransfer(this::captureObserver, this::restoreObserver, this::detachObserver);
        shard.onPoll(() -> {
            for (Runnable command; (command = pending.poll()) != null;) command.run();
        });
    }

    private MinecraftShardBridge.ObserverState captureObserver(long observer) {
        var avatars = visible.get(observer);
        return new MinecraftShardBridge.ObserverState(handles.getOrDefault(observer, ""),
                avatars == null ? java.util.Map.of() : avatars);
    }

    private void restoreObserver(long observer, MinecraftShardBridge.ObserverState state) {
        if (!state.interest().isEmpty()) handles.put(observer, state.interest());
        if (!state.visiblePlayers().isEmpty()) visible.put(observer, new HashMap<>(state.visiblePlayers()));
    }

    private void detachObserver(long observer) {
        visible.remove(observer);
        pending.add(() -> {
            // Core migrates the interest and its membership. Release the source's
            // application references without destroying the transferred interest.
            var interest = interests.remove(observer);
            handles.remove(observer);
            if (interest != null) interest.members.values().forEach(member -> member.active = false);
        });
    }

    private void attachmentChanged(long player, boolean attached) {
        if (attached) {
            pending.add(() -> start(player));
        } else {
            var previous = visible.remove(player);
            if (previous != null) previous.values().forEach(avatar -> removeAvatar(player, avatar));
            pending.add(() -> {
                stop(player);
                interests.forEach((observer, interest) -> {
                    var member = interest.members.get(player);
                    if (member != null) refresh(observer, player, member);
                });
            });
        }
    }

    private void start(long observer) {
        if (!bridge.hasAttachedPlayer(observer) || interests.containsKey(observer)) return;
        var interest = new Interest();
        try {
            interest.handle = handles.get(observer);
            boolean restored = interest.handle != null;
            if (!restored) interest.handle = shard.createEntitySphereInterest(observer, radius, true);
            if (interest.handle == null) return;
            handles.put(observer, interest.handle);
            interests.put(observer, interest);
            shard.setSpatialInterestCallback(interest.handle, (entity, type, revision, positioned, x, y, z) ->
                    event(observer, interest, entity, type, positioned ? new Vec3(x, y, z) : null));
            if (restored) {
                var members = java.util.Set.copyOf(interest.members.keySet());
                server.execute(() -> {
                    var avatars = visible.get(observer);
                    if (avatars != null) {
                        avatars.keySet().stream().filter(entity -> !members.contains(entity)).toList()
                                .forEach(entity -> hide(observer, entity));
                    }
                });
            }
        } catch (GErrorException failure) {
            stop(observer);
            LOG.log(System.Logger.Level.WARNING, "Could not start player visibility", failure);
        }
    }

    private void stop(long observer) {
        var interest = interests.remove(observer);
        handles.remove(observer);
        if (interest == null) return;
        interest.members.values().forEach(member -> member.active = false);
        try {
            shard.destroySpatialInterest(interest.handle);
        } catch (GErrorException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not release player visibility", failure);
        }
    }

    private void event(long observer, Interest interest, long entity, int type, Vec3 position) {
        if (entity == observer) return;
        if (type == EXIT) {
            var member = interest.members.remove(entity);
            if (member != null) member.active = false;
            server.execute(() -> hide(observer, entity));
            return;
        }
        var member = interest.members.computeIfAbsent(entity, ignored -> new Member());
        if (position != null) member.position = position;
        refresh(observer, entity, member);
    }

    private void refresh(long observer, long entity, Member member) {
        if (member.snapshot == null && !member.loading) {
            member.loading = true;
            load(observer, entity, member, 3);
        }
        server.execute(() -> update(observer, entity, member));
    }

    private void load(long observer, long entity, Member member, int attempts) {
        if (!member.active || !bridge.hasAttachedPlayer(observer)) return;
        entities.load(entity).whenComplete((stored, failure) -> {
            if (!member.active) return;
            if (failure == null && stored.isPresent()) {
                var snapshot = stored.get().snapshot();
                if (snapshot.entityType().equals("minecraft:player")) {
                    member.snapshot = snapshot;
                    server.execute(() -> update(observer, entity, member));
                }
                return;
            }
            if (attempts > 1) CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS)
                    .execute(() -> pending.add(() -> load(observer, entity, member, attempts - 1)));
            else member.loading = false;
        });
    }

    private void show(long observer, long entity, Member member, AtlasNetEntityStore.Snapshot snapshot) {
        if (!member.active) return;
        var player = bridge.localPlayer(observer);
        if (player == null || !player.level().dimension().location().toString().equals(snapshot.dimension())) return;
        var avatars = visible.computeIfAbsent(observer, ignored -> new HashMap<>());
        if (avatars.containsKey(entity)) return;
        var data = snapshot.data();
        String name = data.getString("AtlasNetProfileName");
        Vec3 position = member.position;
        if (position == null) {
            // The migrated worker mirror replays member IDs before it has
            // position samples. A stationary member may never send another one.
            var saved = data.getList("Pos", Tag.TAG_DOUBLE);
            if (saved.size() == 3) position = new Vec3(saved.getDouble(0), saved.getDouble(1), saved.getDouble(2));
        }
        if (name.isEmpty() || position == null) return;
        var profile = new GameProfile(snapshot.minecraftId(), name);
        int playId = MinecraftPlayerJoin.playEntityId(profile.getId());
        var rotation = data.getList("Rotation", Tag.TAG_FLOAT);
        float yaw = rotation.size() == 2 ? rotation.getFloat(0) : 0;
        float pitch = rotation.size() == 2 ? rotation.getFloat(1) : 0;
        // Inventory.load only decodes slots. Creating a ServerPlayer here would
        // replace the live advancement owner for the same UUID on this shard.
        var inventory = new Inventory(player);
        inventory.load(data.getList("Inventory", Tag.TAG_COMPOUND));
        int selected = data.getInt("SelectedItemSlot");
        if (selected >= 0 && selected < 9) inventory.selected = selected;
        avatars.put(entity, profile.getId());
        bridge.sendObservedPacket(observer, playerInfo(profile));
        bridge.sendObservedPacket(observer, new ClientboundAddEntityPacket(playId, profile.getId(),
                position.x, position.y, position.z, pitch, yaw, EntityType.PLAYER, 0, Vec3.ZERO, yaw));
        var equipment = new ArrayList<Pair<EquipmentSlot, ItemStack>>();
        for (var slot : EquipmentSlot.values()) {
            var stack = switch (slot) {
                case MAINHAND -> inventory.getSelected();
                case OFFHAND -> inventory.offhand.get(0);
                case FEET, LEGS, CHEST, HEAD -> inventory.armor.get(slot.getIndex());
                default -> ItemStack.EMPTY;
            };
            if (!stack.isEmpty()) equipment.add(Pair.of(slot, stack.copy()));
        }
        if (!equipment.isEmpty()) {
            bridge.sendObservedPacket(observer, new ClientboundSetEquipmentPacket(playId, equipment));
        }
        var poses = net.minecraft.world.entity.Pose.values();
        int pose = data.getInt("AtlasNetPose");
        var metadata = new MinecraftShardBridge.PlayerMetadataState(data.getByte("AtlasNetLivingFlags"),
                data.getByte("AtlasNetSharedFlags"),
                pose >= 0 && pose < poses.length ? poses[pose] : net.minecraft.world.entity.Pose.STANDING);
        bridge.sendObservedPacket(observer, metadata.packet(playId));
    }

    private ClientboundPlayerInfoUpdatePacket playerInfo(GameProfile profile) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            var action = ClientboundPlayerInfoUpdatePacket.Action.class;
            // The vanilla ServerPlayer constructor reads latency from a live connection.
            // Our presentation object is detached, so decode the vanilla ADD_PLAYER fields directly.
            buffer.writeEnumSet(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER), action);
            buffer.writeVarInt(1);
            buffer.writeUUID(profile.getId());
            buffer.writeUtf(profile.getName(), 16);
            ByteBufCodecs.GAME_PROFILE_PROPERTIES.encode(buffer, profile.getProperties());
            return ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private void update(long observer, long entity, Member member) {
        if (member.active && member.snapshot != null) {
            show(observer, entity, member, member.snapshot);
        }
    }

    private void hide(long observer, long entity) {
        var avatars = visible.get(observer);
        if (avatars == null) return;
        var avatar = avatars.remove(entity);
        if (avatar != null) removeAvatar(observer, avatar);
    }

    private void removeAvatar(long observer, UUID avatar) {
        bridge.sendObservedPacket(observer, new ClientboundRemoveEntitiesPacket(MinecraftPlayerJoin.playEntityId(avatar)));
        if (server.getPlayerList().getPlayer(avatar) == null) {
            bridge.sendObservedPacket(observer,
                    new ClientboundPlayerInfoRemovePacket(java.util.List.of(avatar)));
        }
    }
}
