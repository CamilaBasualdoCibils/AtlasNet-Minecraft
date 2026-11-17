package net.atlasnet.minecraft.fabric.mixin;

import net.atlasnet.minecraft.fabric.client.AtlasNetPlayConnection;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class RegionHudMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void renderAtlasNetHud(GuiGraphics graphics, DeltaTracker delta, CallbackInfo callback) {
        var connection = AtlasNetPlayConnection.current();
        if (connection == null) {
            return;
        }
        var font = Minecraft.getInstance().font;
        int y = 8;
        var shard = connection.visibleShardLabel();
        if (shard != null) {
            graphics.drawString(font, shard, 8, y, 0x80ffff);
            y += font.lineHeight + 2;
        }
        var region = connection.visibleRegion();
        if (region != null) {
            graphics.drawString(font, "AtlasNet region " + region.regionId(), 8, y, 0x80ffff);
        }
    }
}
