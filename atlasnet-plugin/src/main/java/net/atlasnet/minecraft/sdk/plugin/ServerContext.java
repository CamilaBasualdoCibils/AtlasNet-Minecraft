package net.atlasnet.minecraft.sdk.plugin;

import java.util.Optional;
import net.atlasnet.minecraft.sdk.core.ResourceKey;

public interface ServerContext {
    /** Looks up a logical Minecraft dimension by its namespaced key. */
    Optional<WorldView> world(ResourceKey key);

    Events events();

    Scheduler scheduler();

    PlayerMessaging messaging();

    EntityAuthority authority();
}
