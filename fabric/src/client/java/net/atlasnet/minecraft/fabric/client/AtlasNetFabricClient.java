package net.atlasnet.minecraft.fabric.client;

import net.atlasnet.minecraft.AtlasNetMinecraft;
import net.fabricmc.api.ClientModInitializer;

public final class AtlasNetFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AtlasNetMinecraft.LOGGER.info("[AtlasNet] Client initialized");
    }
}
