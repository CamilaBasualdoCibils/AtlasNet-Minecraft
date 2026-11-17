package net.atlasnet.minecraft.fabric.server;

import com.mojang.authlib.GameProfile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import net.atlasnet.minecraft.atlasnet.protocol.signals.WorldActorSignal;
import net.atlasnet.minecraft.fabric.mixin.ShardArrowCollisionAccessor;
import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Game-thread state for AtlasNet-managed Overworld items and arrows. */
public final class ShardActors {
    private static final boolean ENABLED = Boolean.getBoolean("atlasnet.minecraft.liveTerrain");
    private static final int MAX_CHECKPOINT_BYTES = 24 * 1024;
    private static final HashMap<Integer, Actor> tracked = new HashMap<>();
    private static final Set<Integer> frozen = new HashSet<>();
    private static final Set<Integer> crossing = new HashSet<>();
    private static final HashMap<String, Checkpoint> incoming = new HashMap<>();
    private static final ConcurrentLinkedQueue<byte[]> frames = new ConcurrentLinkedQueue<>();

    private ShardActors() {}

    public static boolean managed(Entity entity) {
        return ENABLED && entity.level() instanceof ServerLevel level && level.dimension() == ServerLevel.OVERWORLD
                && (entity instanceof ItemEntity || entity instanceof Arrow);
    }

    public static void track(Entity entity) {
        if (managed(entity)) {
            var actor = tracked.computeIfAbsent(entity.getId(), id -> new Actor(entity));
            if (actor.shooter == null && entity instanceof Arrow arrow && arrow.getOwner() instanceof Player player) {
                actor.shooter = player.getGameProfile();
            }
        }
    }

    public static void untrack(Entity entity) {
        if (tracked.remove(entity.getId()) != null) {
            frozen.remove(entity.getId());
            crossing.remove(entity.getId());
            frames.add(ByteBuffer.allocate(Integer.BYTES).putInt(entity.getId()).array());
        }
    }

    public static boolean frozen(Entity entity) {
        return managed(entity) && (frozen.contains(entity.getId()) || crossing.contains(entity.getId()));
    }

    public static Player firingPlayer(Projectile projectile) {
        var actor = tracked.get(projectile.getId());
        if (actor == null || actor.shooter == null) {
            return null;
        }
        if (actor.detachedShooter == null) {
            actor.detachedShooter = new DetachedShooter((ServerLevel) projectile.level(), actor.shooter);
        }
        return actor.detachedShooter;
    }

    static void sample() {
        for (var entry : tracked.entrySet()) {
            var actor = entry.getValue();
            Entity entity = actor.entity;
            int id = entity.getId();
            boolean owned = ShardChunkIO.ownsPosition(entity.getX(), entity.getZ());
            if (owned) {
                crossing.remove(id);
            }
            if (entity.isRemoved() || frozen(entity)) {
                continue;
            }
            if (!owned) {
                crossing.add(id);
            } else {
                actor.lastOwnedPosition = entity.position();
            }
            byte[] item = actor.itemState();
            if (item == null) {
                continue;
            }
            byte[] frame = ByteBuffer.allocate(Integer.BYTES + WorldActorSignal.STATE_BYTES + item.length)
                    .putInt(entity.getId()).putDouble(entity.getX()).putDouble(entity.getY()).putDouble(entity.getZ())
                    .putFloat(entity.getYRot()).putFloat(entity.getXRot())
                    .putLong(entity.getUUID().getMostSignificantBits()).putLong(entity.getUUID().getLeastSignificantBits())
                    .put(entity instanceof ItemEntity ? WorldActorSignal.ITEM : WorldActorSignal.ARROW)
                    .put(item).array();
            if (!Arrays.equals(frame, actor.previous)) {
                actor.previous = frame;
                frames.add(frame);
            }
        }
    }

    static byte[][] drain() {
        var pending = new java.util.ArrayList<byte[]>();
        for (int i = 0; i < 256; i++) {
            byte[] frame = frames.poll();
            if (frame == null) {
                break;
            }
            pending.add(frame);
        }
        return pending.toArray(byte[][]::new);
    }

    static byte[] freeze(int id) {
        var actor = tracked.get(id);
        if (actor == null || actor.entity.isRemoved()) {
            return null;
        }
        var entity = actor.entity;
        var tag = new CompoundTag();
        if (!entity.save(tag)) {
            return null;
        }
        if (actor.shooter != null) {
            tag.putUUID("AtlasNetShooter", actor.shooter.getId());
            tag.putString("AtlasNetShooterName", actor.shooter.getName());
        }
        if (entity instanceof Arrow && crossing.contains(id)) {
            tag.putDouble("AtlasNetFromX", actor.lastOwnedPosition.x());
            tag.putDouble("AtlasNetFromY", actor.lastOwnedPosition.y());
            tag.putDouble("AtlasNetFromZ", actor.lastOwnedPosition.z());
        }
        byte[] state = encode(tag, MAX_CHECKPOINT_BYTES);
        if (state != null) {
            frozen.add(id);
        }
        return state;
    }

    static void release(int id, boolean commit) {
        var actor = tracked.get(id);
        frozen.remove(id);
        crossing.remove(id);
        if (commit && actor != null) {
            actor.entity.discard();
        }
    }

    static boolean stage(ServerLevel level, String transfer, byte[] state) {
        if (!ENABLED || state.length == 0 || state.length > MAX_CHECKPOINT_BYTES) {
            return false;
        }
        try {
            var tag = NbtIo.readCompressed(new ByteArrayInputStream(state), NbtAccounter.create(256 * 1024));
            var entity = EntityType.loadEntityRecursive(tag, level, Function.identity());
            if (entity == null || !managed(entity) || level.getEntity(entity.getUUID()) != null) {
                return false;
            }
            var shooter = tag.hasUUID("AtlasNetShooter")
                    ? new GameProfile(tag.getUUID("AtlasNetShooter"), tag.getString("AtlasNetShooterName")) : null;
            var start = tag.contains("AtlasNetFromX")
                    ? new Vec3(tag.getDouble("AtlasNetFromX"), tag.getDouble("AtlasNetFromY"),
                            tag.getDouble("AtlasNetFromZ")) : null;
            incoming.put(transfer, new Checkpoint(entity, shooter, start));
            return true;
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    static int activate(String transfer) {
        var checkpoint = incoming.get(transfer);
        if (checkpoint == null) {
            return 0;
        }
        var entity = checkpoint.entity;
        if (!((ServerLevel) entity.level()).addFreshEntity(entity)) {
            return 0;
        }
        incoming.remove(transfer);
        // A newly loaded entity may start tracking later if its chunk is still
        // being promoted. Preserve its identity and shooter before collision.
        var actor = tracked.computeIfAbsent(entity.getId(), id -> new Actor(entity));
        actor.shooter = checkpoint.shooter;
        if (entity instanceof Arrow arrow && checkpoint.start != null) {
            var collision = (ShardArrowCollisionAccessor) arrow;
            var end = arrow.position();
            var hit = ProjectileUtil.getEntityHitResult(arrow.level(), arrow, checkpoint.start, end,
                    new AABB(checkpoint.start, end).inflate(1), collision::atlasnet$canHitEntity);
            if (hit != null) {
                collision.atlasnet$hitEntity(hit);
            }
        }
        return entity.getId();
    }

    static void abort(String transfer) {
        incoming.remove(transfer);
    }

    private static byte[] encode(CompoundTag tag, int maxBytes) {
        try {
            var bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bytes);
            return bytes.size() <= maxBytes ? bytes.toByteArray() : null;
        } catch (IOException invalid) {
            return null;
        }
    }

    private record Checkpoint(Entity entity, GameProfile shooter, Vec3 start) {}

    private static final class Actor {
        final Entity entity;
        byte[] previous;
        Vec3 lastOwnedPosition;
        GameProfile shooter;
        Player detachedShooter;
        ItemStack lastItem = ItemStack.EMPTY;
        byte[] itemBytes = new byte[0];

        Actor(Entity entity) {
            this.entity = entity;
            lastOwnedPosition = entity.position();
        }

        byte[] itemState() {
            if (entity instanceof ItemEntity drop && !ItemStack.matches(lastItem, drop.getItem())) {
                lastItem = drop.getItem().copy();
                itemBytes = encode((CompoundTag) lastItem.saveOptional(entity.registryAccess()),
                        WorldActorSignal.MAX_ITEM_BYTES);
            }
            return itemBytes;
        }
    }

    private static final class DetachedShooter extends Player {
        DetachedShooter(ServerLevel level, GameProfile shooter) {
            super(level, level.getSharedSpawnPos(), 0, shooter);
        }

        @Override public boolean isCreative() { return false; }
        @Override public boolean isSpectator() { return false; }
    }
}
