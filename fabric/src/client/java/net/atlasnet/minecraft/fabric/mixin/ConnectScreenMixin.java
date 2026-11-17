package net.atlasnet.minecraft.fabric.mixin;

import net.atlasnet.minecraft.fabric.client.AtlasNetConnectScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void connectThroughAtlasNet(Screen parent, Minecraft minecraft, ServerAddress address,
            ServerData server, boolean quickPlay, TransferState transfer, CallbackInfo callback) {
        String host = address.getHost();
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        minecraft.setScreen(new AtlasNetConnectScreen(parent, host + ":" + address.getPort(), server));
        callback.cancel();
    }
}
