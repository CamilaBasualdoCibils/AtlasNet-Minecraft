package net.atlasnet.minecraft.sdk.mod.server;

import net.atlasnet.minecraft.sdk.mod.ContentRegistry;
import net.atlasnet.minecraft.sdk.plugin.Plugin;

public interface ServerMod extends Plugin {
    /** Declares content during the loader's registration phase, before server enable. */
    void registerContent(ContentRegistry registry);
}
