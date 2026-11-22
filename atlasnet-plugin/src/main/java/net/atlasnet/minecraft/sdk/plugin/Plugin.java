package net.atlasnet.minecraft.sdk.plugin;

/** Lifecycle invoked by a platform adapter on its server execution context. */
public interface Plugin {
    void onEnable(ServerContext server);

    /** Releases resources acquired during enable. */
    default void onDisable() {
    }
}
