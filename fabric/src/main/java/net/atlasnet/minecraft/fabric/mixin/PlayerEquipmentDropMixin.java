package net.atlasnet.minecraft.fabric.mixin;

import net.atlasnet.minecraft.fabric.server.ShardPlayers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class PlayerEquipmentDropMixin implements ShardPlayers.EquipmentDropAccess {
    @Shadow protected abstract void dropEquipment();

    @Inject(method = "dropEquipment", at = @At("HEAD"), cancellable = true)
    private void atlasnet$delayEquipmentDrop(CallbackInfo callback) {
        if ((Object) this instanceof ServerPlayer player && ShardPlayers.deferEquipmentDrop(player)) {
            callback.cancel();
        }
    }

    @Override
    public void atlasnet$dropEquipment() {
        dropEquipment();
    }
}
