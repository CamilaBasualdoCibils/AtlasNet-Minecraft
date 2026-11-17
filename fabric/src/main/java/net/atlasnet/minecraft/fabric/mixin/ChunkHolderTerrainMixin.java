package net.atlasnet.minecraft.fabric.mixin;

import java.util.List;
import net.atlasnet.minecraft.fabric.server.ShardPlayers;
import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkHolder.class)
abstract class ChunkHolderTerrainMixin {
    @Redirect(method = "broadcastChanges", at = @At(value = "INVOKE",
            target = "Ljava/util/List;isEmpty()Z", ordinal = 1))
    private boolean hasNoBlockViewers(List<ServerPlayer> viewers) {
        return viewers.isEmpty() && !ShardChunkIO.usesCollisionReplicas()
                && !ShardPlayers.remoteTerrainViewers(((ChunkHolder) (Object) this).getPos());
    }

    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void routeTrackedBlockChanges(List<ServerPlayer> viewers, Packet<?> packet, CallbackInfo callback) {
        if (ShardChunkIO.current() != null && (packet instanceof ClientboundBlockUpdatePacket
                || packet instanceof ClientboundSectionBlocksUpdatePacket
                || packet instanceof ClientboundBlockEntityDataPacket)) {
            ChunkPos chunk = ((ChunkHolder) (Object) this).getPos();
            if (ShardChunkIO.ownsTerrain(chunk.toLong())) {
                ShardPlayers.recordCollisionChanges(chunk, packet);
                ShardPlayers.sendRemoteTerrain(chunk, packet);
                for (var viewer : viewers) {
                    ShardPlayers.sendTrackedTerrain(viewer, chunk, packet);
                }
            }
            callback.cancel();
        }
    }
}
