package net.atlasnet.minecraft.fabric.mixin;

import java.util.Optional;
import net.atlasnet.minecraft.fabric.server.ShardPlayers;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerList.class)
abstract class PlayerTransferDataMixin {
    @Inject(method = "load", at = @At("HEAD"), cancellable = true)
    private void useTransferredData(ServerPlayer player, CallbackInfoReturnable<Optional<CompoundTag>> result) {
        CompoundTag data = ShardPlayers.transferData(player);
        if (data != null) {
            result.setReturnValue(Optional.of(data));
        }
    }
}
