package net.atlasnet.minecraft.fabric.mixin;

import java.io.IOException;
import java.net.InetAddress;
import java.util.function.BooleanSupplier;
import net.atlasnet.minecraft.fabric.server.ShardPlayers;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DedicatedServer.class)
abstract class DedicatedServerMixin {
    @Inject(method = "initServer", at = @At("RETURN"))
    private void captureShardServer(CallbackInfoReturnable<Boolean> result) {
        if (Boolean.getBoolean("atlasnet.minecraft.shard") && result.getReturnValue()) {
            ShardPlayers.ready((DedicatedServer) (Object) this);
        }
    }

    @Inject(method = "tickChildren", at = @At("TAIL"))
    private void tickShardConnections(BooleanSupplier hasTime, CallbackInfo callback) {
        if (Boolean.getBoolean("atlasnet.minecraft.shard")) {
            ShardPlayers.tick();
        }
    }

    @Redirect(method = "initServer", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/network/ServerConnectionListener;startTcpServerListener(Ljava/net/InetAddress;I)V"))
    private void startListener(ServerConnectionListener listener, InetAddress address, int port) throws IOException {
        if (!Boolean.getBoolean("atlasnet.minecraft.shard")) {
            listener.startTcpServerListener(address, port);
        }
    }
}
