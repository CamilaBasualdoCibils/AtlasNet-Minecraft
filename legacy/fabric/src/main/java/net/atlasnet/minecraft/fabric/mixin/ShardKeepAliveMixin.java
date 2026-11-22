package net.atlasnet.minecraft.fabric.mixin;

import net.atlasnet.minecraft.fabric.server.ShardPlayers;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
abstract class ShardKeepAliveMixin {
    @Shadow @Final protected Connection connection;

    @Inject(method = "keepConnectionAlive", at = @At("HEAD"), cancellable = true)
    private void atlasnet$keepAliveAtIngress(CallbackInfo callback) {
        if (ShardPlayers.ownsConnection(connection)) {
            callback.cancel();
        }
    }
}
