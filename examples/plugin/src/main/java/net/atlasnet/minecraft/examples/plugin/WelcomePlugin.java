package net.atlasnet.minecraft.examples.plugin;

import net.atlasnet.minecraft.sdk.plugin.Events;
import net.atlasnet.minecraft.sdk.plugin.Plugin;
import net.atlasnet.minecraft.sdk.plugin.ServerContext;

public final class WelcomePlugin implements Plugin {
    private Events.Subscription joins;

    @Override
    public void onEnable(ServerContext server) {
        joins = server.events().onPlayerJoin(player -> server.messaging().sendSystemMessage(
                player.entity().id(), "Welcome, " + player.displayName() + "!"));
    }

    @Override
    public void onDisable() {
        joins.close();
    }
}
