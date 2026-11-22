package net.atlasnet.minecraft.examples.mod.server;

import net.atlasnet.minecraft.sdk.core.ResourceKey;
import net.atlasnet.minecraft.sdk.mod.ContentRegistry;
import net.atlasnet.minecraft.sdk.mod.CustomContent;
import net.atlasnet.minecraft.sdk.mod.server.ServerMod;
import net.atlasnet.minecraft.sdk.plugin.Events;
import net.atlasnet.minecraft.sdk.plugin.ServerContext;

public final class LanternServerMod implements ServerMod {
    private static final CustomContent.Block LANTERN =
            new CustomContent.Block(new ResourceKey("example", "lantern"));

    private Events.Subscription joins;

    @Override
    public void registerContent(ContentRegistry registry) {
        registry.registerBlock(LANTERN);
    }

    @Override
    public void onEnable(ServerContext server) {
        joins = server.events().onPlayerJoin(player ->
                server.messaging().sendSystemMessage(player.entity().id(), "Lantern content is enabled."));
    }

    @Override
    public void onDisable() {
        joins.close();
    }
}
