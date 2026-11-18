package net.atlasnet.minecraft.test.mixin;

import net.atlasnet.minecraft.fabric.server.ShardPlayers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShardPlayers.class, remap = false)
abstract class PlayerHandoffTimingMixin {
    @Unique private static long atlasnet$started;

    @Inject(method = {"freezePlayer", "stagePlayer", "activatePlayer", "extractPlayer"}, at = @At("HEAD"))
    private static void atlasnet$begin(CallbackInfoReturnable<?> callback) {
        atlasnet$started = System.nanoTime();
    }

    @Inject(method = {"freezePlayer", "stagePlayer", "activatePlayer", "extractPlayer"}, at = @At("RETURN"))
    private static void atlasnet$end(CallbackInfoReturnable<?> callback) {
        System.out.println("MIGRATION_TEST phase_ms=" + (System.nanoTime() - atlasnet$started) / 1e6);
    }

    @Inject(method = "takePose", at = @At("RETURN"))
    private static void atlasnet$pose(CallbackInfoReturnable<double[]> callback) {
        var pose = callback.getReturnValue();
        if (pose != null) {
            System.out.println("MIGRATION_TEST pose time=" + System.currentTimeMillis()
                    + " x=" + pose[0] + " vx=" + pose[15]);
        }
    }
}
