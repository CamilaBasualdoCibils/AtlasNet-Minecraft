package net.atlasnet.minecraft.server;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import org.atlasnet.atlasnet.Shard;
import org.atlasnet.atlasnet.StateStore;
import org.gnome.glib.MainContext;
import org.gnome.glib.Variant;
import org.javagi.base.GErrorException;

/** Tracks accessible full chunks through the Minecraft 1.21.1 status callback. */
public final class MinecraftChunkSubscriptions {
    private static final System.Logger LOG = System.getLogger(MinecraftChunkSubscriptions.class.getName());
    private record Chunk(ResourceLocation dimension, int x, int z) {
        String key() {
            return dimension + "/" + x + "/" + z + "/subscribers";
        }
    }
    private record Change(Chunk chunk, boolean loaded) {}
    private static final class Membership {
        boolean desired;
        boolean present;
        boolean inFlight;
    }

    private final ConcurrentLinkedQueue<Change> changes = new ConcurrentLinkedQueue<>();
    private final HashMap<Chunk, Membership> memberships = new HashMap<>();
    private final StateStore store;
    private final Variant shardId;

    public MinecraftChunkSubscriptions(Shard shard, UUID id) throws GErrorException {
        store = shard.getStateStore("minecraft.chunks");
        shardId = Variant.string(id.toString());
        shard.onPoll(this::poll);
        shard.onPollEvents(MinecraftChunkSubscriptions::dispatchCallbacks);
    }

    void observe(ServerLevel level) {
        try {
            Field field = ChunkMap.class.getDeclaredField("chunkStatusListener");
            field.setAccessible(true);
            var chunkMap = level.getChunkSource().chunkMap;
            var original = (ChunkStatusUpdateListener) field.get(chunkMap);
            var dimension = level.dimension().location();
            ChunkStatusUpdateListener listener = (position, status) -> {
                original.onChunkStatusChange(position, status);
                changes.add(new Change(new Chunk(dimension, position.x, position.z),
                        status.isOrAfter(FullChunkStatus.FULL)));
            };
            field.set(chunkMap, listener);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot attach the Minecraft 1.21.1 chunk status observer", failure);
        }
    }

    private void poll() {
        dispatchCallbacks();
        for (Change change; (change = changes.poll()) != null;) {
            var membership = memberships.computeIfAbsent(change.chunk(), ignored -> new Membership());
            membership.desired = change.loaded();
            reconcile(change.chunk(), membership);
        }
    }

    private static void dispatchCallbacks() {
        var context = MainContext.default_();
        for (int i = 0; i < 1024 && context.iteration(false); i++) {
            // Existing shard callbacks run on the worker thread.
        }
    }

    /** Begin asynchronous removal on the worker thread after the game server stops. */
    public void close() {
        changes.clear();
        memberships.forEach((chunk, membership) -> membership.desired = false);
        for (var entry : new HashMap<>(memberships).entrySet()) {
            reconcile(entry.getKey(), entry.getValue());
        }
    }

    private void reconcile(Chunk chunk, Membership membership) {
        if (membership.inFlight) return;
        if (membership.desired == membership.present) {
            if (!membership.present) memberships.remove(chunk);
            return;
        }
        boolean add = membership.desired;
        try {
            var subscribers = store.getSet(chunk.key());
            membership.inFlight = true;
            if (add) {
                subscribers.addAsync(shardId, result -> finish(chunk, membership, true,
                        () -> subscribers.addFinish(result)));
            } else {
                subscribers.removeAsync(shardId, result -> finish(chunk, membership, false,
                        () -> subscribers.removeFinish(result)));
            }
        } catch (GErrorException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not open chunk subscribers for " + chunk.key(), failure);
        }
    }

    private void finish(Chunk chunk, Membership membership, boolean add, StoreFinish finish) {
        membership.inFlight = false;
        try {
            finish.run();
            membership.present = add;
        } catch (GErrorException failure) {
            // An interrupted acknowledgment may have followed a successful write.
            membership.present = true;
            LOG.log(System.Logger.Level.WARNING, "Could not update chunk subscribers for " + chunk.key(), failure);
            if (!add || membership.desired) return;
        }
        reconcile(chunk, membership);
    }

    @FunctionalInterface
    private interface StoreFinish {
        void run() throws GErrorException;
    }
}
