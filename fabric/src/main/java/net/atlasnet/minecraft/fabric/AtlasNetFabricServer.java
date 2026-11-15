package net.atlasnet.minecraft.fabric;

import net.atlasnet.minecraft.AtlasNetMinecraft;
import net.fabricmc.api.DedicatedServerModInitializer;

public final class AtlasNetFabricServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        AtlasNetMinecraft.LOGGER.info("[AtlasNet] Dedicated server initialized");
    }
}
