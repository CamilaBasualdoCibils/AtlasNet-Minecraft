package net.atlasnet.minecraft.fabric.mixin;

import java.util.Map;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MinecraftServer.class)
abstract class ShardWorldsMixin {
    @Redirect(method = "createLevels", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/Registry;entrySet()Ljava/util/Set;"))
    private Set<Map.Entry<ResourceKey<LevelStem>, LevelStem>> additionalDimensions(Registry<LevelStem> stems) {
        // Minecraft creates the Overworld before it iterates the remaining dimension stems.
        return Boolean.getBoolean("atlasnet.minecraft.shard") ? Set.of() : stems.entrySet();
    }
}
