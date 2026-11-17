package net.atlasnet.minecraft.fabric.client;

import com.mojang.authlib.GameProfile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerPoseSignal;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;

final class RemotePlayers {
    private record Tracked(RemotePlayer player, long revision) {}
    private final Map<Long, Tracked> visible = new HashMap<>();
    private final Map<Integer, Long> entityIds = new HashMap<>();
    private int nextId = -1;

    void apply(ClientLevel level, PlayerPoseSignal pose, boolean show) {
        var tracked = visible.get(pose.entity());
        if (tracked == null) {
            if (!show) {
                return;
            }
            String name = String.format("AN%014x", pose.client() & 0x00ffffffffffffffL);
            var profile = new GameProfile(new UUID(0x41544c41534e4554L, pose.client()), name);
            var player = new RemotePlayer(level, profile);
            player.setId(nextId--);
            player.moveTo(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
            level.addEntity(player);
            entityIds.put(player.getId(), pose.entity());
            tracked = new Tracked(player, pose.revision());
        } else if (Long.compareUnsigned(pose.revision(), tracked.revision()) <= 0) {
            return;
        }
        var player = tracked.player();
        player.lerpTo(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch(), 3);
        player.lerpHeadTo(pose.headYaw(), 3);
        player.setShiftKeyDown(pose.crouching());
        player.setPose(pose.crouching() ? Pose.CROUCHING : Pose.STANDING);
        player.setSprinting(pose.sprinting());
        player.setOnGround(pose.onGround());
        visible.put(pose.entity(), new Tracked(player, pose.revision()));
    }

    void remove(ClientLevel level, long id) {
        var tracked = visible.remove(id);
        if (tracked != null) {
            int entityId = tracked.player().getId();
            entityIds.remove(entityId);
            if (level != null) {
                level.removeEntity(entityId, Entity.RemovalReason.DISCARDED);
            }
        }
    }

    Long atlasEntity(int localId) {
        return entityIds.get(localId);
    }

    void removeMatchingPlayer(ClientLevel level, UUID profile) {
        var players = visible.values().iterator();
        while (players.hasNext()) {
            var tracked = players.next();
            if (tracked.player().getUUID().equals(profile)) {
                entityIds.remove(tracked.player().getId());
                if (level != null) {
                    level.removeEntity(tracked.player().getId(), Entity.RemovalReason.DISCARDED);
                }
                players.remove();
                return;
            }
        }
    }
}
