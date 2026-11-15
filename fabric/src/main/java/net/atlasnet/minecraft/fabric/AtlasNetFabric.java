package net.atlasnet.minecraft.fabric;

import net.atlasnet.minecraft.AtlasNetMinecraft;
import net.fabricmc.api.ModInitializer;

public final class AtlasNetFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        AtlasNetMinecraft.initialize();
        AtlasNetMinecraft.LOGGER.info("[AtlasNet] Fabric platform initialized");
    }
}
