package net.atlasnet.minecraft.host.inventory;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongFunction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ItemStack;
import org.atlasnet.atlasnet.Shard;
import org.javagi.base.GErrorException;

/** Reads vanilla container slots from the authoritative entity without materializing a replica container. */
public final class AtlasNetInventory {
    private static final String METHOD = "minecraft.inventory.read.v1";
    private static final int MAX_SLOTS = 256;

    private final MinecraftServer server;
    private final Shard shard;
    private final AtlasNetBlockEntity blocks;
    private final LongFunction<Entity> localEntity;
    private final ConcurrentLinkedQueue<Runnable> requests = new ConcurrentLinkedQueue<>();

    public AtlasNetInventory(MinecraftServer server, Shard shard, AtlasNetBlockEntity blocks,
                            LongFunction<Entity> localEntity) throws GErrorException {
        this.server = server;
        this.shard = shard;
        this.blocks = blocks;
        this.localEntity = localEntity;
        if (!shard.bindEntityRpc(METHOD, this::receive)) {
            throw new IllegalStateException("Could not bind inventory EntityRPC");
        }
        shard.onPoll(() -> {
            for (int i = 0; i < 32; i++) {
                var request = requests.poll();
                if (request == null) break;
                request.run();
            }
        });
    }

    /** Read the slots of a player or an entity implementing vanilla Container. */
    public CompletableFuture<Optional<List<ItemStack>>> readEntity(long entity) {
        var result = new CompletableFuture<Optional<List<ItemStack>>>();
        requests.add(() -> request(entity, new byte[0], result));
        return result;
    }

    /** Read the slots of a persistent block inventory through its chunk EntityID. */
    public CompletableFuture<Optional<List<ItemStack>>> readBlock(AtlasNetBlockEntity.Address address) {
        var result = new CompletableFuture<Optional<List<ItemStack>>>();
        requests.add(() -> blocks.owner(address).whenComplete((owner, failure) -> {
            if (failure != null) result.completeExceptionally(failure);
            else if (owner.isEmpty()) result.complete(Optional.empty());
            else {
                try {
                    var buffer = buffer(new byte[0]);
                    try {
                        buffer.writeResourceLocation(address.dimension());
                        buffer.writeBlockPos(address.position());
                        byte[] payload = new byte[buffer.readableBytes()];
                        buffer.readBytes(payload);
                        request(owner.getAsLong(), payload, result);
                    } finally {
                        buffer.release();
                    }
                } catch (RuntimeException error) {
                    result.completeExceptionally(error);
                }
            }
        }));
        return result;
    }

    private void request(long entity, byte[] payload, CompletableFuture<Optional<List<ItemStack>>> result) {
        try {
            shard.callEntityRpcAsync(entity, METHOD, payload, operation -> {
                try {
                    result.complete(decode(shard.callEntityRpcFinish(operation)));
                } catch (GErrorException | RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    private byte[] receive(long entity, byte[] payload) {
        var input = buffer(payload);
        try {
            var address = input.isReadable()
                    ? new AtlasNetBlockEntity.Address(input.readResourceLocation(), input.readBlockPos()) : null;
            if (input.isReadable()) return null;
            return server.submit(() -> encode(resolve(entity, address))).join();
        } catch (RuntimeException failure) {
            return null;
        } finally {
            input.release();
        }
    }

    private Container resolve(long entity, AtlasNetBlockEntity.Address address) {
        if (address != null) {
            return blocks.local(entity, address) instanceof Container inventory ? inventory : null;
        }
        return switch (localEntity.apply(entity)) {
            case ServerPlayer player -> player.getInventory();
            case Container inventory -> inventory;
            case null, default -> null;
        };
    }

    private byte[] encode(Container inventory) {
        if (inventory == null) return new byte[0];
        // Vanilla slot reads may unpack loot with a null player. Preserve
        // deferred loot generation for the owner-side interaction.
        if (inventory instanceof RandomizableContainer block && block.getLootTable() != null
                || inventory instanceof ContainerEntity vehicle && vehicle.getLootTable() != null) {
            return new byte[0];
        }
        int count = inventory.getContainerSize();
        if (count > MAX_SLOTS) return null;
        var output = buffer(new byte[0]);
        try {
            output.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(output, inventory.getItem(i));
            }
            byte[] response = new byte[output.readableBytes()];
            output.readBytes(response);
            return response;
        } finally {
            output.release();
        }
    }

    private Optional<List<ItemStack>> decode(byte[] response) {
        if (response.length == 0) return Optional.empty();
        var input = buffer(response);
        try {
            int count = input.readVarInt();
            if (count < 0 || count > MAX_SLOTS) throw new IllegalArgumentException("Invalid inventory slot count");
            var items = new ArrayList<ItemStack>(count);
            for (int i = 0; i < count; i++) items.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(input));
            if (input.isReadable()) throw new IllegalArgumentException("Trailing inventory slot data");
            return Optional.of(List.copyOf(items));
        } finally {
            input.release();
        }
    }

    private RegistryFriendlyByteBuf buffer(byte[] bytes) {
        return new RegistryFriendlyByteBuf(bytes.length == 0 ? Unpooled.buffer() : Unpooled.wrappedBuffer(bytes),
                server.registryAccess());
    }
}
