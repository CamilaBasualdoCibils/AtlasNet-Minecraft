package net.atlasnet.minecraft.atlasnet.protocol.signals;

import java.util.List;
import java.util.Objects;

/** An authoritative AtlasNet region. A missing shape means its geometry is unavailable. */
public record PlayerRegionSignal(long regionId, long generation, Shape shape) {
    /** Minecraft world XYZ. XZ region geometry is unbounded along Y. */
    public record Point(double x, double y, double z) {
    }

    /** Interior is normal dot position <= offset, or < offset for an exclusive plane. */
    public record Plane(Point normal, double offset, boolean exclusive) {
        public Plane {
            Objects.requireNonNull(normal);
            if (!Double.isFinite(normal.x()) || !Double.isFinite(normal.y())
                    || !Double.isFinite(normal.z()) || !Double.isFinite(offset)) {
                throw new IllegalArgumentException("Invalid region plane");
            }
        }
    }

    public record Shape(int dimension, Point min, Point max, List<Plane> planes) {
        public Shape {
            if (dimension != 2 && dimension != 3) {
                throw new IllegalArgumentException("Invalid region dimension");
            }
            if (!(min.x() <= max.x() && min.y() <= max.y() && min.z() <= max.z())) {
                throw new IllegalArgumentException("Invalid region bounds");
            }
            planes = List.copyOf(planes);
        }
    }
}
