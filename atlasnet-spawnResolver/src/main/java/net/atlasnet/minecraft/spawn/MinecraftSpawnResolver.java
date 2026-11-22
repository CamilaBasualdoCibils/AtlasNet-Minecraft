package net.atlasnet.minecraft.spawn;

import java.util.Objects;
import org.atlasnet.atlasnet.Callbacks;
import org.atlasnet.atlasnet.Registry;
import org.atlasnet.atlasnet.SpawnPlacement;
import org.atlasnet.atlasnet.SpawnResolver;
import org.javagi.base.GErrorException;

/** Provides the configured initial world and position for single-shard placement. */
public record MinecraftSpawnResolver(String world, double x, double y, double z)
        implements SpawnResolver {
    /** Temporary initial AtlasNet placement for the first single-shard deployment. */
    public static final MinecraftSpawnResolver DEFAULT = new MinecraftSpawnResolver(
            "minecraft:overworld", 0, 150, 0);

    public MinecraftSpawnResolver {
        Objects.requireNonNull(world, "world");
        if (world.isBlank()) {
            throw new IllegalArgumentException("Spawn world must not be blank");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Spawn coordinates must be finite");
        }
    }

    public void register(Registry registry, String name) throws GErrorException {
        registry.registerSpawnResolver(name, Callbacks.spawnResolver(this));
    }

    @Override
    public SpawnPlacement run(long clientId, byte[] admissionData) {
        var placement = new SpawnPlacement();
        placement.setWorldName(world);
        placement.writeX(x);
        placement.writeY(y);
        placement.writeZ(z);
        return placement;
    }
}
