package net.atlasnet.minecraft.sdk.mod.server;

import java.util.concurrent.CompletionStage;
import net.atlasnet.minecraft.sdk.plugin.EntitySnapshot;

/** Adapter-managed synchronization of a modded client's custom content definitions. */
public interface ContentSynchronization {
    /** Completes when the player has synchronized content, or fails if synchronization fails. */
    CompletionStage<Void> synchronize(EntitySnapshot.Id player);
}
