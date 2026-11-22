package net.atlasnet.minecraft.examples.mod.client;

import net.atlasnet.minecraft.sdk.core.ResourceKey;
import net.atlasnet.minecraft.sdk.mod.ContentRegistry;
import net.atlasnet.minecraft.sdk.mod.CustomContent;
import net.atlasnet.minecraft.sdk.mod.client.ClientMod;

public final class LanternClientMod implements ClientMod {
    @Override
    public void registerContent(ContentRegistry registry) {
        registry.registerBlock(new CustomContent.Block(new ResourceKey("example", "lantern")));
    }
}
