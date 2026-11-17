package net.atlasnet.minecraft.fabric.server.storage;

import java.util.Objects;
import net.atlasnet.minecraft.atlasnet.world.AtlasNetWorlds;
import net.minecraft.resources.ResourceLocation;

public record ChunkKey(ResourceLocation dimension, int x, int z) {
    public ChunkKey {
        Objects.requireNonNull(dimension, "dimension");
        if (!AtlasNetWorlds.OVERWORLD_DIMENSION.equals(dimension.toString())) {
            throw new IllegalArgumentException("Unsupported chunk dimension " + dimension);
        }
    }

    String storageKey() {
        return "minecraft:v1:chunk:" + dimension + ":" + x + ":" + z;
    }
}
