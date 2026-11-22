package net.atlasnet.minecraft.sdk.mod.client;

import net.atlasnet.minecraft.sdk.mod.ContentRegistry;

/** Client mod contract invoked by a future client adapter during content registration. */
public interface ClientMod {
    void registerContent(ContentRegistry registry);
}
