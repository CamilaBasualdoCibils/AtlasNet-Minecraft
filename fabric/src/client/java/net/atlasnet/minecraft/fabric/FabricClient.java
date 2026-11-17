package net.atlasnet.minecraft.fabric;

import net.atlasnet.minecraft.DemoStartup;
import net.fabricmc.api.ClientModInitializer;

public final class FabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DemoStartup.log("client");
    }
}
