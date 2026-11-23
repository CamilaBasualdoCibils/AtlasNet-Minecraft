package net.atlasnet.minecraft.server;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

/** Observes vanilla 1.21.1 entity tracking and gates ordinary entity ticking. */
public final class MinecraftEntityLifecycle {
    public interface Observer {
        void trackingStarted(Entity entity, Runnable startTicking, Runnable stopTicking);
        void trackingEnded(Entity entity);
        void destroyed(Entity entity);
        boolean mayTick(Entity entity);
        default void tick() {}
        default void beforeShutdown() {}
        default CompletableFuture<ChunkEntities<Entity>> restoreChunk(
                ServerLevel level, ChunkPos position, ChunkEntities<Entity> original) {
            return CompletableFuture.completedFuture(original);
        }
    }

    private MinecraftEntityLifecycle() {}

    /** Install on the game thread, before the level begins loading entities. */
    @SuppressWarnings("unchecked")
    public static void install(ServerLevel level, Observer observer) {
        try {
            var entityManager = ServerLevel.class.getDeclaredField("entityManager");
            entityManager.setAccessible(true);
            var manager = entityManager.get(level);
            var callbacks = PersistentEntitySectionManager.class.getDeclaredField("callbacks");
            callbacks.setAccessible(true);
            var vanilla = (LevelCallback<Entity>) callbacks.get(manager);
            callbacks.set(manager, new ObservedCallbacks(vanilla, observer));
            var storage = PersistentEntitySectionManager.class.getDeclaredField("permanentStorage");
            storage.setAccessible(true);
            var original = (EntityPersistentStorage<Entity>) storage.get(manager);
            storage.set(manager, new EntityPersistentStorage<Entity>() {
                @Override public CompletableFuture<ChunkEntities<Entity>> loadEntities(ChunkPos position) {
                    return original.loadEntities(position)
                            .thenCompose(entities -> observer.restoreChunk(level, position, entities));
                }

                @Override public void storeEntities(ChunkEntities<Entity> entities) { original.storeEntities(entities); }
                @Override public void flush(boolean force) { original.flush(force); }
                @Override public void close() throws IOException { original.close(); }
            });
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot attach Minecraft 1.21.1 entity lifecycle observer", failure);
        }
    }

    private static final class ObservedCallbacks implements LevelCallback<Entity> {
        private final LevelCallback<Entity> vanilla;
        private final Observer observer;
        private final java.util.Set<Entity> requestedTicks =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private final java.util.Set<Entity> activeTicks =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        ObservedCallbacks(LevelCallback<Entity> vanilla, Observer observer) {
            this.vanilla = vanilla;
            this.observer = observer;
        }

        @Override public void onCreated(Entity entity) { vanilla.onCreated(entity); }

        @Override public void onDestroyed(Entity entity) {
            try { observer.destroyed(entity); }
            finally { vanilla.onDestroyed(entity); }
        }

        @Override public void onTickingStart(Entity entity) {
            requestedTicks.add(entity);
            startTicking(entity);
        }

        private void startTicking(Entity entity) {
            if (requestedTicks.contains(entity) && observer.mayTick(entity) && activeTicks.add(entity))
                vanilla.onTickingStart(entity);
        }

        @Override public void onTickingEnd(Entity entity) {
            requestedTicks.remove(entity);
            stopTicking(entity);
        }

        private void stopTicking(Entity entity) {
            if (activeTicks.remove(entity)) vanilla.onTickingEnd(entity);
        }

        @Override public void onTrackingStart(Entity entity) {
            vanilla.onTrackingStart(entity);
            observer.trackingStarted(entity, () -> startTicking(entity), () -> stopTicking(entity));
        }

        @Override public void onTrackingEnd(Entity entity) {
            try { observer.trackingEnded(entity); }
            finally { vanilla.onTrackingEnd(entity); }
        }

        @Override public void onSectionChange(Entity entity) { vanilla.onSectionChange(entity); }
    }
}
