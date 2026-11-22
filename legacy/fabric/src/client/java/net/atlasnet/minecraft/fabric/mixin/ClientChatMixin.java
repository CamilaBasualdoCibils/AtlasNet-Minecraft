package net.atlasnet.minecraft.fabric.mixin;

import net.atlasnet.minecraft.fabric.client.AtlasNetPlayConnection;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientChatMixin {
    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void sendAtlasNetChat(String message, CallbackInfo callback) {
        Connection connection = ((ClientPacketListener) (Object) this).getConnection();
        if (connection instanceof AtlasNetPlayConnection atlasNet) {
            atlasNet.chat(message);
            callback.cancel();
        }
    }

    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void runAtlasNetCommand(String command, CallbackInfo callback) {
        Connection connection = ((ClientPacketListener) (Object) this).getConnection();
        if (connection instanceof AtlasNetPlayConnection atlasNet) {
            atlasNet.command(command);
            callback.cancel();
        }
    }
}
