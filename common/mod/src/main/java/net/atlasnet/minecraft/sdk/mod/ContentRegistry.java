package net.atlasnet.minecraft.sdk.mod;

/** Registration-phase facade supplied by a future mod loader adapter. */
public interface ContentRegistry {
    void registerBlock(CustomContent.Block block);

    void registerItem(CustomContent.Item item);

    void registerEntity(CustomContent.Entity entity);
}
