package net.atlasnet.minecraft.fabric.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerRegionSignal.Shape;
import net.atlasnet.minecraft.fabric.client.AtlasNetPlayConnection;
import net.atlasnet.minecraft.fabric.client.rendering.AtlasNetRegionOutline;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugRenderer.class)
abstract class RegionOutlineMixin {
    @Unique private Shape atlasnet$shape;
    @Unique private List<AtlasNetRegionOutline.Edge> atlasnet$edges = List.of();

    @Inject(method = "render", at = @At("TAIL"))
    private void atlasnet$renderRegion(PoseStack pose, MultiBufferSource.BufferSource buffers,
                                      double x, double y, double z, CallbackInfo callback) {
        var connection = AtlasNetPlayConnection.current();
        var region = connection == null ? null : connection.visibleRegion();
        var shape = region == null ? null : region.shape();
        if (shape != atlasnet$shape) {
            atlasnet$shape = shape;
            atlasnet$edges = AtlasNetRegionOutline.edges(shape);
        }
        if (connection == null || !connection.showsRegion()) {
            return;
        }

        var vertex = buffers.getBuffer(RenderType.lines());
        var transform = pose.last();
        AtlasNetRegionOutline.drawChunkGrid(vertex, transform, x, y, z);
        for (int i = 0; i < atlasnet$edges.size(); i++) {
            atlasnet$edges.get(i).draw(vertex, transform, x, z);
        }
    }
}
