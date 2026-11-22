package net.atlasnet.minecraft.fabric.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.atlasnet.minecraft.atlasnet.protocol.signals.WorldActorSignal;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.ItemStack;

final class RemoteWorldActors {
    private final Map<Long, Tracked> visible = new HashMap<>();
    private final Map<Long, WorldActorSignal> pending = new HashMap<>();
    private int nextId = Integer.MIN_VALUE;

    void apply(ClientLevel level, WorldActorSignal pose, boolean show) throws IOException {
        if (level == null) {
            if (show) {
                pending.put(pose.entity(), pose);
            } else {
                pending.computeIfPresent(pose.entity(), (id, old) ->
                        Long.compareUnsigned(pose.revision(), old.revision()) > 0 ? pose : old);
            }
            return;
        }
        var current = visible.get(pose.entity());
        if (current == null && !show || current != null && Long.compareUnsigned(pose.revision(), current.revision) <= 0) {
            return;
        }
        ItemStack item = null;
        if (pose.item().length > 0) {
            var tag = NbtIo.readCompressed(new ByteArrayInputStream(pose.item()), NbtAccounter.create(128 * 1024));
            item = ItemStack.parseOptional(level.registryAccess(), tag);
            if (item.isEmpty()) {
                return;
            }
        }
        Entity entity;
        if (current == null) {
            entity = switch (pose.kind()) {
                case WorldActorSignal.ITEM -> new ItemEntity(level, pose.x(), pose.y(), pose.z(), item);
                case WorldActorSignal.ARROW -> EntityType.ARROW.create(level);
                case WorldActorSignal.PROJECTILE -> projectile(level, pose.projectileType());
                default -> null;
            };
            if (entity == null) {
                return;
            }
            if (entity instanceof ThrowableItemProjectile projectile && item != null) {
                projectile.setItem(item);
            }
            entity.setNoGravity(true);
            entity.setId(nextId++);
            entity.setUUID(pose.uuid());
            entity.moveTo(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
            level.addEntity(entity);
        } else {
            entity = current.entity;
            if (entity instanceof ItemEntity drop && item != null) {
                drop.setItem(item);
            } else if (entity instanceof ThrowableItemProjectile projectile && item != null) {
                projectile.setItem(item);
            }
            entity.lerpTo(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch(), 2);
        }
        visible.put(pose.entity(), new Tracked(entity, pose.revision()));
    }

    private static Entity projectile(ClientLevel level, String location) {
        var id = ResourceLocation.tryParse(location);
        if (id == null) {
            return null;
        }
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        var entity = type == null ? null : type.create(level);
        return entity instanceof Projectile ? entity : null;
    }

    void attach(ClientLevel level) {
        if (level == null || pending.isEmpty()) {
            return;
        }
        for (var pose : pending.values()) {
            try {
                apply(level, pose, true);
            } catch (IOException invalid) {
                // A malformed actor snapshot cannot be rendered.
            }
        }
        pending.clear();
    }

    void remove(ClientLevel level, long id) {
        pending.remove(id);
        var tracked = visible.remove(id);
        if (tracked != null && level != null) {
            level.removeEntity(tracked.entity.getId(), Entity.RemovalReason.DISCARDED);
        }
    }

    private record Tracked(Entity entity, long revision) {}
}
