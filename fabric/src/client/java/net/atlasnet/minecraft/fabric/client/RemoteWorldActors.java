package net.atlasnet.minecraft.fabric.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.atlasnet.minecraft.atlasnet.protocol.signals.WorldActorSignal;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
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
        if (pose.kind() == WorldActorSignal.ITEM) {
            var tag = NbtIo.readCompressed(new ByteArrayInputStream(pose.item()), NbtAccounter.create(128 * 1024));
            item = ItemStack.parseOptional(level.registryAccess(), tag);
            if (item.isEmpty()) {
                return;
            }
        }
        Entity entity;
        if (current == null) {
            entity = item == null ? EntityType.ARROW.create(level)
                    : new ItemEntity(level, pose.x(), pose.y(), pose.z(), item);
            if (entity == null) {
                return;
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
            }
            entity.lerpTo(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch(), 2);
        }
        visible.put(pose.entity(), new Tracked(entity, pose.revision()));
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
