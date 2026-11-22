package net.atlasnet.minecraft.sdk.plugin;

import java.util.function.Consumer;

/** Callbacks run on the adapter's execution context for the event. */
public interface Events {
    /** Reports a player joining the game session. Shard migrations do not count as joins. */
    Subscription onPlayerJoin(Consumer<? super Player> listener);

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
