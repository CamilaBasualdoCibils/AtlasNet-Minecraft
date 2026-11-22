package net.atlasnet.minecraft.sdk.core;

/** A namespaced Minecraft resource identifier, such as a dimension or content key. */
public record ResourceKey(String namespace, String path) {
}
