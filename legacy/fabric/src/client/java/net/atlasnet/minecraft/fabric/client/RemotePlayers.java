package net.atlasnet.minecraft.fabric.client;

import com.mojang.authlib.GameProfile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerPoseSignal;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

final class RemotePlayers {
    /** Rendering prediction only; authority and gameplay remain on the shard. */
    private static final class Replica extends RemotePlayer {
        private int snapshotAge;
        private float motionX;
        private float motionY;
        private float motionZ;
        private long motionSequence;
        private boolean predictedMotion;

        Replica(ClientLevel level, GameProfile profile) {
            super(level, profile);
        }

        void snapshot(PlayerPoseSignal pose) {
            long elapsed = pose.motionSequence() - motionSequence;
            boolean continuing = motionX * pose.motionX() + motionY * pose.motionY()
                    + motionZ * pose.motionZ() > 0;
            // A late source pose can arrive after we have visually predicted
            // past it. Age it by the existing client movement sequence instead
            // of restarting interpolation at its older checkpoint.
            snapshotAge = predictedMotion && continuing && elapsed >= 0 && elapsed < snapshotAge
                    ? snapshotAge - (int) elapsed : 0;
            motionSequence = pose.motionSequence();
            motionX = pose.motionX();
            motionY = pose.motionY();
            motionZ = pose.motionZ();
            double dx = pose.x() - getX();
            double dy = pose.y() - getY();
            double dz = pose.z() - getZ();
            double speedSquared = motionX * motionX + motionY * motionY + motionZ * motionZ;
            if (predictedMotion && continuing && elapsed >= 0
                    && dx * dx + dy * dy + dz * dz <= 49 * speedSquared
                    && dx * motionX + dy * motionY + dz * motionZ < 2 * speedSquared) {
                // Several late samples can arrive in one client tick. Keep
                // the rendered trajectory until authority catches up, within
                // the same seven-tick prediction bound, without rewinding it.
                snapshotAge = Math.max(3, snapshotAge);
                lerpTo(getX(), getY(), getZ(), pose.yaw(), pose.pitch(), 1);
                return;
            }
            predictedMotion = false;
            int lead = Math.clamp(snapshotAge - 3, 0, 7);
            lerpTo(pose.x() + motionX * lead, pose.y() + motionY * lead, pose.z() + motionZ * lead,
                    pose.yaw(), pose.pitch(), lead > 0 ? 1 : 3);
        }

        @Override
        public void tick() {
            super.tick();
            // Vanilla interpolates the first three ticks. Bridge short frozen
            // authority gaps using the last server-measured velocity, bounded
            // to half a second overall; never extrapolate indefinitely.
            if (++snapshotAge > 3 && snapshotAge <= 10) {
                setPos(getX() + motionX, getY() + motionY, getZ() + motionZ);
                predictedMotion = true;
            }
        }
    }
    private record Tracked(Replica player, PlayerPoseSignal pose) {}
    private final Map<Long, Tracked> visible = new HashMap<>();
    private final Map<Integer, Long> entityIds = new HashMap<>();
    private int nextId = -1;

    void apply(ClientLevel level, PlayerPoseSignal pose, boolean show) {
        var tracked = visible.get(pose.entity());
        PlayerPoseSignal previous = tracked == null ? null : tracked.pose();
        if (tracked == null) {
            if (!show) {
                return;
            }
            String name = String.format("AN%014x", pose.client() & 0x00ffffffffffffffL);
            var profile = new GameProfile(new UUID(0x41544c41534e4554L, pose.client()), name);
            var player = new Replica(level, profile);
            player.setId(nextId--);
            player.moveTo(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
            level.addEntity(player);
            entityIds.put(player.getId(), pose.entity());
            tracked = new Tracked(player, pose);
        } else if (Long.compareUnsigned(pose.revision(), previous.revision()) <= 0) {
            return;
        }
        var player = tracked.player();
        player.snapshot(pose);
        player.lerpHeadTo(pose.headYaw(), 3);
        player.setShiftKeyDown(pose.crouching());
        player.setPose(pose.fallFlying() ? Pose.FALL_FLYING : pose.swimming() ? Pose.SWIMMING
                : pose.crouching() && !pose.flying() ? Pose.CROUCHING : Pose.STANDING);
        player.setSprinting(pose.sprinting());
        player.setSwimming(pose.swimming());
        player.setOnGround(pose.onGround());
        if (pose.fallFlying() && !player.isFallFlying()) {
            player.startFallFlying();
        } else if (!pose.fallFlying() && player.isFallFlying()) {
            player.stopFallFlying();
        }
        updateItem(player, EquipmentSlot.MAINHAND, pose.mainItem(), previous == null ? -1 : previous.mainItem());
        updateItem(player, EquipmentSlot.OFFHAND, pose.offItem(), previous == null ? -1 : previous.offItem());
        updateItem(player, EquipmentSlot.HEAD, pose.headItem(), previous == null ? -1 : previous.headItem());
        updateItem(player, EquipmentSlot.CHEST, pose.chestItem(), previous == null ? -1 : previous.chestItem());
        updateItem(player, EquipmentSlot.LEGS, pose.legsItem(), previous == null ? -1 : previous.legsItem());
        updateItem(player, EquipmentSlot.FEET, pose.feetItem(), previous == null ? -1 : previous.feetItem());
        if (pose.usingItem()) {
            var hand = pose.usingOffhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            if (!player.isUsingItem() || player.getUsedItemHand() != hand) {
                player.startUsingItem(hand);
            }
        } else if (player.isUsingItem()) {
            player.stopUsingItem();
        }
        if (previous != null && pose.mainSwing() != previous.mainSwing()) {
            player.swing(InteractionHand.MAIN_HAND);
        } else if (previous != null && pose.offSwing() != previous.offSwing()) {
            player.swing(InteractionHand.OFF_HAND);
        }
        visible.put(pose.entity(), new Tracked(player, pose));
    }

    private static void updateItem(RemotePlayer player, EquipmentSlot slot, int item, int previous) {
        if (item != previous) {
            player.setItemSlot(slot, new ItemStack(BuiltInRegistries.ITEM.byId(item)));
        }
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
