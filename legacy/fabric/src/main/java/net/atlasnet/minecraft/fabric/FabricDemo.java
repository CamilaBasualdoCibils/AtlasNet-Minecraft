package net.atlasnet.minecraft.fabric;

import net.atlasnet.minecraft.DemoStartup;
import net.fabricmc.api.ModInitializer;

public final class FabricDemo implements ModInitializer {
    @Override
    public void onInitialize() {
        DemoStartup.log("common");
    }
}
