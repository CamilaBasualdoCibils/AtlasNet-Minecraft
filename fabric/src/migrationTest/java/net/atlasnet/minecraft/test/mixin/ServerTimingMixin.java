package net.atlasnet.minecraft.test.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class ServerTimingMixin {
    @Unique private long atlasnet$started;
    @Unique private long atlasnet$previous;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void atlasnet$begin(BooleanSupplier time, CallbackInfo callback) {
        long now = System.nanoTime();
        if (atlasnet$previous != 0 && now - atlasnet$previous > 150_000_000L) {
            System.out.println("MIGRATION_TEST tick_gap_ms=" + (now - atlasnet$previous) / 1e6
                    + " time=" + System.currentTimeMillis());
        }
        atlasnet$started = atlasnet$previous = now;
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void atlasnet$end(BooleanSupplier time, CallbackInfo callback) {
        double elapsed = (System.nanoTime() - atlasnet$started) / 1e6;
        if (elapsed > 100) {
            System.out.println("MIGRATION_TEST tick_ms=" + elapsed + " time=" + System.currentTimeMillis());
        }
    }
}
