package net.atlasnet.minecraft.fabric;

import net.atlasnet.minecraft.DemoStartup;
import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.fabricmc.api.DedicatedServerModInitializer;

public final class FabricDedicatedServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        ShardChunkIO.initialize();
        DemoStartup.log("dedicated server");
    }
}
