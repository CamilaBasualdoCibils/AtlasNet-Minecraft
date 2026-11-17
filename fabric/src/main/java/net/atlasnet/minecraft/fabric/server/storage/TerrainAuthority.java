package net.atlasnet.minecraft.fabric.server.storage;

import net.minecraft.world.level.ChunkPos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** AtlasNet authority for Minecraft chunk centers in the XZ plane. */
record TerrainAuthority(double minX, double minZ, double maxX, double maxZ, List<Plane> planes) {
    static TerrainAuthority fromWorker() {
        String value = System.getProperty("atlasnet.minecraft.terrainBounds");
        if (value == null) {
            return new TerrainAuthority(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, List.of());
        }
        return fromBounds(Arrays.stream(value.split(",", -1)).mapToDouble(Double::parseDouble).toArray());
    }

    static TerrainAuthority fromBounds(double[] bounds) {
        if (bounds.length < 4 || (bounds.length - 4) % 4 != 0) {
            throw new IllegalArgumentException("Invalid AtlasNet terrain region");
        }
        var planes = new ArrayList<Plane>();
        for (int i = 4; i < bounds.length; i += 4) {
            planes.add(new Plane(bounds[i], bounds[i + 1], bounds[i + 2], bounds[i + 3] != 0));
        }
        return new TerrainAuthority(bounds[0], bounds[1], bounds[2], bounds[3], List.copyOf(planes));
    }

    boolean owns(long chunk) {
        return contains(ChunkPos.getX(chunk) * 16.0 + 8.0, ChunkPos.getZ(chunk) * 16.0 + 8.0);
    }

    boolean contains(double x, double z) {
        if (!(x >= minX && x < maxX && z >= minZ && z < maxZ)) {
            return false;
        }
        for (var plane : planes) {
            if (!plane.contains(x, z)) {
                return false;
            }
        }
        return true;
    }

    record Plane(double nx, double nz, double offset, boolean exclusive) {
        boolean contains(double x, double z) {
            double px = nx * x;
            double pz = nz * z;
            double difference = px + pz - offset;
            double error = 8 * Math.ulp(Math.abs(px) + Math.abs(pz) + Math.abs(offset));
            if (Math.abs(difference) > error) {
                return difference < 0;
            }
            // AtlasNet uses extended precision on the boundary. Preserve the
            // exact binary inputs here so neighboring shards agree on ties.
            int side = new BigDecimal(nx).multiply(new BigDecimal(x))
                    .add(new BigDecimal(nz).multiply(new BigDecimal(z)))
                    .compareTo(new BigDecimal(offset));
            return exclusive ? side < 0 : side <= 0;
        }
    }
}
