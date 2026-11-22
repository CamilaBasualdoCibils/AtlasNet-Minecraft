package net.atlasnet.minecraft.sdk.mod;

import net.atlasnet.minecraft.sdk.core.ResourceKey;

/** Shared content declarations. A platform adapter supplies the corresponding behavior. */
public interface CustomContent {
    ResourceKey key();

    record Block(ResourceKey key) implements CustomContent {
    }

    record Item(ResourceKey key) implements CustomContent {
    }

    record Entity(ResourceKey key) implements CustomContent {
    }
}
