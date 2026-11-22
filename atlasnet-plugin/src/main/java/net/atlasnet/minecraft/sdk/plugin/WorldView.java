package net.atlasnet.minecraft.sdk.plugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.atlasnet.minecraft.sdk.core.ResourceKey;

/** Read access to the chunks available in this adapter's execution context. */
public interface WorldView {
    ResourceKey key();

    /** Looks up a loaded chunk without causing a load. Coordinates are chunk coordinates. */
    Optional<Chunk> loadedChunk(int chunkX, int chunkZ);

    /**
     * Finds locally authoritative entities in this world whose positions lie in the box.
     * Results are snapshots and may become stale as entities move or migrate.
     */
    List<EntitySnapshot> findLocalEntities(Box bounds);

    interface Chunk {
        /** Looks up a vanilla block by absolute block coordinates in this chunk. */
        Optional<VanillaBlock> blockAt(int x, int y, int z);
    }

    /** Block type and its vanilla state properties. */
    interface VanillaBlock {
        ResourceKey key();

        Map<String, String> properties();
    }

    /** Axis-aligned bounds in Minecraft coordinates, inclusive on all sides. */
    record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }
}
