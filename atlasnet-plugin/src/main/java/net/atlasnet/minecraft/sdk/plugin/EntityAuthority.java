package net.atlasnet.minecraft.sdk.plugin;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Observes authority and invokes game-defined operations on the authoritative shard. */
public interface EntityAuthority {
    /** Returns the known owner at lookup time. The answer may become stale during migration. */
    CompletionStage<Optional<Owner>> ownerOf(EntitySnapshot.Id entity);

    /**
     * Routes a named operation to the entity's authority. Implementations complete exceptionally
     * on routing or execution failure. Callers must schedule follow-up game work on its owning
     * execution context because completion can run on another thread.
     */
    <I, O> CompletionStage<O> invoke(EntitySnapshot.Id entity, Operation<I, O> operation, I input);

    /** Adapter-supplied identifiers for a node and its shard. */
    record Owner(String node, String shard) {
    }

    /** A namespaced operation key and its types. The adapter supplies handlers and serialization. */
    record Operation<I, O>(String key, Class<I> inputType, Class<O> resultType) {
    }
}
