package net.atlasnet.minecraft.sdk.plugin;

import java.util.UUID;
import net.atlasnet.minecraft.sdk.core.ResourceKey;

/** An immutable observation of an entity at a point in time. */
public record EntitySnapshot(Id id, ResourceKey typeKey, ResourceKey worldKey, double x, double y, double z) {
    /** Logical identity that survives authority migration, independent of platform runtime IDs. */
    public record Id(UUID value) {
    }
}
