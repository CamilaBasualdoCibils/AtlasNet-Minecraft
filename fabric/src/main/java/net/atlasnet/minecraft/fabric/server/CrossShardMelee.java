package net.atlasnet.minecraft.fabric.server;

import com.mojang.authlib.GameProfile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Holder;

/** Runs the hit on the target owner using a snapshot taken by the attacker's owner. */
final class CrossShardMelee {
    private static final int MAX_STATE_BYTES = 12 * 1024;
    private final Map<ServerPlayer, PendingSwing> pending = new HashMap<>();

    byte[] prepare(ServerPlayer player) {
        if (player == null || !player.isAlive() || player.isSpectator()
                || ShardPlayers.isFrozen(player) || pending.containsKey(player)) {
            return null;
        }
        var state = new CompoundTag();
        state.putUUID("UUID", player.getUUID());
        state.putString("Name", player.getGameProfile().getName());
        state.putDouble("X", player.getX());
        state.putDouble("Y", player.getY());
        state.putDouble("Z", player.getZ());
        state.putFloat("Yaw", player.getYRot());
        state.putFloat("Pitch", player.getXRot());
        state.putFloat("Strength", player.getAttackStrengthScale(0.5F));
        state.putFloat("FallDistance", player.fallDistance);
        state.putBoolean("Ground", player.onGround());
        state.putBoolean("Sprinting", player.isSprinting());
        state.putBoolean("Crouching", player.isShiftKeyDown());
        state.putBoolean("Creative", player.isCreative());
        state.putDouble("Damage", player.getAttributeValue(Attributes.ATTACK_DAMAGE));
        state.putDouble("Knockback", player.getAttributeValue(Attributes.ATTACK_KNOCKBACK));
        state.putDouble("Reach", player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE));
        state.putDouble("Sweeping", player.getAttributeValue(Attributes.SWEEPING_DAMAGE_RATIO));
        var weapon = player.getMainHandItem().copy();
        state.put("Weapon", weapon.saveOptional(player.registryAccess()));
        byte[] bytes = encode(state);
        if (bytes != null) {
            player.resetAttackStrengthTicker();
            pending.put(player, new PendingSwing(player.getInventory().selected, weapon));
        }
        return bytes;
    }

    static byte[] hit(ServerPlayer target, byte[] bytes) {
        if (target == null || !target.isAlive() || target.isSpectator() || ShardPlayers.isFrozen(target)) {
            return null;
        }
        var state = decode(bytes);
        if (state == null || !state.hasUUID("UUID") || target.getUUID().equals(state.getUUID("UUID"))) {
            return null;
        }
        var attacker = new AttackSnapshot(target.serverLevel(), state);
        if (!target.canHarmPlayer(attacker) || !attacker.canInteractWithEntity(target, 0)
                || !attacker.hasLineOfSight(target)) {
            return null;
        }
        attacker.attack(target);
        var result = new CompoundTag();
        result.put("Weapon", attacker.getMainHandItem().saveOptional(target.registryAccess()));
        result.putFloat("Exhaustion", attacker.exhaustion);
        return encode(result);
    }

    void finish(ServerPlayer player, byte[] bytes) {
        var swing = pending.remove(player);
        var result = decode(bytes);
        if (swing == null || result == null || ShardPlayers.isFrozen(player)) {
            return;
        }
        var inventory = player.getInventory();
        if (ItemStack.matches(inventory.getItem(swing.slot()), swing.weapon())) {
            inventory.setItem(swing.slot(), ItemStack.parseOptional(player.registryAccess(), result.getCompound("Weapon")));
        }
        player.causeFoodExhaustion(result.getFloat("Exhaustion"));
    }

    void discard(ServerPlayer player) {
        pending.remove(player);
    }

    private static byte[] encode(CompoundTag tag) {
        try {
            var bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bytes);
            return bytes.size() <= MAX_STATE_BYTES ? bytes.toByteArray() : null;
        } catch (IOException invalid) {
            return null;
        }
    }

    private static CompoundTag decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_STATE_BYTES) {
            return null;
        }
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(128 * 1024));
        } catch (IOException invalid) {
            return null;
        }
    }

    private record PendingSwing(int slot, ItemStack weapon) {}

    // This player is never added to the level or ticked. Vanilla uses it as the
    // attacker for cooldown, item bonuses, knockback, damage, and equipment wear.
    private static final class AttackSnapshot extends Player {
        private final float strength;
        private final boolean creative;
        private float exhaustion;

        AttackSnapshot(ServerLevel level, CompoundTag tag) {
            super(level, level.getSharedSpawnPos(), tag.getFloat("Yaw"),
                    new GameProfile(tag.getUUID("UUID"), tag.getString("Name")));
            moveTo(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"),
                    tag.getFloat("Yaw"), tag.getFloat("Pitch"));
            strength = tag.getFloat("Strength");
            creative = tag.getBoolean("Creative");
            fallDistance = tag.getFloat("FallDistance");
            setOnGround(tag.getBoolean("Ground"));
            setSprinting(tag.getBoolean("Sprinting"));
            setShiftKeyDown(tag.getBoolean("Crouching"));
            getInventory().setItem(0, ItemStack.parseOptional(registryAccess(), tag.getCompound("Weapon")));
            setAttribute(Attributes.ATTACK_DAMAGE, tag.getDouble("Damage"));
            setAttribute(Attributes.ATTACK_KNOCKBACK, tag.getDouble("Knockback"));
            setAttribute(Attributes.ENTITY_INTERACTION_RANGE, tag.getDouble("Reach"));
            setAttribute(Attributes.SWEEPING_DAMAGE_RATIO, tag.getDouble("Sweeping"));
        }

        private void setAttribute(Holder<Attribute> attribute, double value) {
            var instance = getAttribute(attribute);
            if (instance != null) {
                instance.setBaseValue(value);
            }
        }

        @Override
        public float getAttackStrengthScale(float partialTick) {
            return strength;
        }

        @Override
        public void causeFoodExhaustion(float amount) {
            exhaustion += amount;
        }

        @Override
        public boolean isCreative() {
            return creative;
        }

        @Override
        public boolean isSpectator() {
            return false;
        }
    }
}
