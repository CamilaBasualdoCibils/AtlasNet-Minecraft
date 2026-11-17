package net.atlasnet.minecraft.fabric.client.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerRegionSignal.Shape;
import net.minecraft.core.SectionPos;

public final class AtlasNetRegionOutline {
    private static final double RADIUS = 64;
    private static final float HEIGHT = 24;

    private AtlasNetRegionOutline() {}

    public static void drawChunkGrid(VertexConsumer vertex, PoseStack.Pose transform,
                                     double cameraX, double cameraY, double cameraZ) {
        int chunkSize = SectionPos.SECTION_SIZE;
        int radius = 2 * chunkSize;
        double centerX = Math.floor(cameraX / chunkSize) * chunkSize - cameraX;
        double centerZ = Math.floor(cameraZ / chunkSize) * chunkSize - cameraZ;
        float height = (float) (Math.floor(cameraY) - cameraY - 1);
        for (int offset = -radius; offset <= radius + chunkSize; offset += chunkSize) {
            float x = (float) (centerX + offset);
            float z = (float) (centerZ + offset);
            float minX = (float) (centerX - radius);
            float minZ = (float) (centerZ - radius);
            float maxX = (float) (centerX + radius + chunkSize);
            float maxZ = (float) (centerZ + radius + chunkSize);
            point(vertex, transform, x, height, minZ, 0, 0, 1, 65, 150, 255);
            point(vertex, transform, x, height, maxZ, 0, 0, 1, 65, 150, 255);
            point(vertex, transform, minX, height, z, 1, 0, 0, 65, 150, 255);
            point(vertex, transform, maxX, height, z, 1, 0, 0, 65, 150, 255);
        }
    }

    public static List<Edge> edges(Shape shape) {
        if (shape == null || shape.dimension() != 2) {
            return List.of();
        }
        var boundaries = new ArrayList<Boundary>();
        addBoundary(boundaries, -1, 0, -shape.min().x());
        addBoundary(boundaries, 1, 0, shape.max().x());
        addBoundary(boundaries, 0, -1, -shape.min().z());
        addBoundary(boundaries, 0, 1, shape.max().z());
        for (var plane : shape.planes()) {
            addBoundary(boundaries, plane.normal().x(), plane.normal().z(), plane.offset());
        }

        var edges = new ArrayList<Edge>();
        for (var boundary : boundaries) {
            var edge = boundary.clip(boundaries);
            if (edge != null) {
                edges.add(edge);
            }
        }
        return List.copyOf(edges);
    }

    private static void addBoundary(List<Boundary> boundaries, double nx, double nz, double offset) {
        double length = Math.hypot(nx, nz);
        if (Double.isFinite(length) && length > 0 && Double.isFinite(offset)) {
            boundaries.add(new Boundary(nx / length, nz / length, offset / length));
        }
    }

    private record Boundary(double nx, double nz, double offset) {
        Edge clip(List<Boundary> boundaries) {
            double x = nx * offset;
            double z = nz * offset;
            double dx = -nz;
            double dz = nx;
            double from = Double.NEGATIVE_INFINITY;
            double to = Double.POSITIVE_INFINITY;
            for (var other : boundaries) {
                if (other == this) {
                    continue;
                }
                double direction = other.nx * dx + other.nz * dz;
                double distance = other.offset - (other.nx * x + other.nz * z);
                if (direction > 1e-12) {
                    to = Math.min(to, distance / direction);
                } else if (direction < -1e-12) {
                    from = Math.max(from, distance / direction);
                } else if (distance < -1e-8) {
                    return null;
                }
            }
            return from < to ? new Edge(x, z, dx, dz, from, to) : null;
        }
    }

    public record Edge(double x, double z, double dx, double dz, double from, double to) {
        public void draw(VertexConsumer vertex, PoseStack.Pose transform, double cameraX, double cameraZ) {
            double relativeX = x - cameraX;
            double relativeZ = z - cameraZ;
            if (dx == 0 && Math.abs(relativeX) > RADIUS || dz == 0 && Math.abs(relativeZ) > RADIUS) {
                return;
            }
            double first = from;
            double last = to;
            if (dx != 0) {
                first = Math.max(first, (-Math.copySign(RADIUS, dx) - relativeX) / dx);
                last = Math.min(last, (Math.copySign(RADIUS, dx) - relativeX) / dx);
            }
            if (dz != 0) {
                first = Math.max(first, (-Math.copySign(RADIUS, dz) - relativeZ) / dz);
                last = Math.min(last, (Math.copySign(RADIUS, dz) - relativeZ) / dz);
            }
            if (!(first < last)) {
                return;
            }

            float x1 = (float) (relativeX + first * dx);
            float z1 = (float) (relativeZ + first * dz);
            float x2 = (float) (relativeX + last * dx);
            float z2 = (float) (relativeZ + last * dz);
            for (int marker = 0; marker <= 8; marker++) {
                float fraction = marker / 8.0F;
                float markerX = x1 + (x2 - x1) * fraction;
                float markerZ = z1 + (z2 - z1) * fraction;
                point(vertex, transform, markerX, -HEIGHT, markerZ, 0, 1, 0);
                point(vertex, transform, markerX, HEIGHT, markerZ, 0, 1, 0);
            }
            for (float height = -HEIGHT; height <= HEIGHT; height += HEIGHT) {
                point(vertex, transform, x1, height, z1, (float) dx, 0, (float) dz);
                point(vertex, transform, x2, height, z2, (float) dx, 0, (float) dz);
            }
        }

    }

    private static void point(VertexConsumer vertex, PoseStack.Pose transform,
                                  float x, float y, float z, float nx, float ny, float nz) {
        point(vertex, transform, x, y, z, nx, ny, nz, 255, 145, 35);
    }

    private static void point(VertexConsumer vertex, PoseStack.Pose transform,
                              float x, float y, float z, float nx, float ny, float nz,
                              int red, int green, int blue) {
        vertex.addVertex(transform, x, y, z)
                .setColor(red, green, blue, 255)
                .setNormal(transform, nx, ny, nz);
    }
}
