package net.atlasnet.minecraft.test.mixin;

import net.atlasnet.minecraft.test.MigrationProbe;

import net.atlasnet.minecraft.fabric.client.AtlasNetPlayConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;

@Mixin(AtlasNetPlayConnection.class)
abstract class ClientPacketsMixin {
    @Inject(method = "receiveNotice", at = @At("HEAD"), cancellable = true)
    private void atlasnet$pose(int id, byte[] payload, CallbackInfo callback) {
        if (MigrationProbe.pose(id, payload)) {
            callback.cancel();
        }
    }

    @Inject(method = "receiveGamePacket", at = @At("HEAD"))
    private void atlasnet$packet(byte[] payload, CallbackInfo callback) {
        MigrationProbe.packet(payload);
    }

    @Inject(method = "continueLocalMotion", at = @At("RETURN"))
    private void atlasnet$correction(ClientboundPlayerPositionPacket position,
                                    CallbackInfoReturnable<Boolean> result) {
        MigrationProbe.handoffPosition(result.getReturnValue());
    }
}
