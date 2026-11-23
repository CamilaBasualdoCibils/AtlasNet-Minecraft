package net.atlasnet.minecraft.host.entity;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;
import java.util.function.LongFunction;
import java.util.function.LongUnaryOperator;
import io.netty.buffer.Unpooled;
import net.atlasnet.minecraft.server.MinecraftShardBridge;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.atlasnet.atlasnet.Shard;
import org.javagi.base.GErrorException;

/** Routes a bounded set of vanilla operations via the existing EntityRPC binding. */
public final class VanillaEntityOperations {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    private static final ResourceLocation PROBE_EFFECT = ResourceLocation.withDefaultNamespace("glowing");

    private static final String METHOD = "minecraft.entity.operation.v1";
    private static final int APPLY_EFFECT = 1;
    private final MinecraftServer server;
    private final LongFunction<Entity> localEntity;
    private final PlayerEntityPublication publication;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();

    public VanillaEntityOperations(MinecraftServer server, LongFunction<Entity> localEntity,
                                   PlayerEntityPublication publication) {
        this.server = server;
        this.localEntity = localEntity;
        this.publication = publication;
    }

    public void register(Shard shard) throws GErrorException {
        shard.onPoll(() -> {
            for (int i = 0; i < 32; i++) {
                var command = commands.poll();
                if (command == null) break;
                command.run();
            }
        });
        shard.bindEntityRpc(METHOD, (entity, bytes) -> {
            try {
                var operation = decode(bytes);
                return server.submit(() -> apply(entity, operation)).join();
            } catch (RuntimeException | IOException failure) {
                return null;
            }
        });
    }

    /** Register the operator commands on the Minecraft server thread. */
    public void registerCommands(Shard shard, UUID shardId, AtlasNetEntityStore store,
                                 ToLongFunction<Entity> entityId, LongUnaryOperator clientId) {
        var read = Commands.literal("read").then(Commands.argument("entity_id", StringArgumentType.word())
                .executes(context -> readSnapshot(context, store)));
        var amplifier = Commands.argument("amplifier", IntegerArgumentType.integer(0, 255))
                .executes(context -> requestEffect(context, shard));
        var ticks = Commands.argument("ticks", IntegerArgumentType.integer(1, 72000)).then(amplifier);
        var effect = Commands.argument("effect", ResourceLocationArgument.id()).then(ticks);
        var apply = Commands.literal("effect")
                .then(Commands.argument("entity_id", StringArgumentType.word()).then(effect));
        var identify = Commands.literal("id").then(Commands.argument("entity", EntityArgument.entity())
                .executes(context -> {
                    long id = entityId.applyAsLong(EntityArgument.getEntity(context, "entity"));
                    if (id == 0) {
                        context.getSource().sendFailure(Component.literal("Entity has no active local AtlasNet ID"));
                        return 0;
                    }
                    context.getSource().sendSuccess(() -> Component.literal(Long.toUnsignedString(id)), false);
                    return 1;
                }));
        var delay = Commands.argument("seconds", IntegerArgumentType.integer(5, 120))
                .executes(context -> scheduleProbe(context, shard, shardId, store, clientId));
        var probe = Commands.literal("probe").then(Commands.argument("entity_id", StringArgumentType.word())
                .then(delay));
        server.getCommands().getDispatcher().register(Commands.literal("atlasnetentity")
                .requires(source -> source.hasPermission(2)).then(identify).then(read).then(apply).then(probe));
    }

    private int readSnapshot(CommandContext<CommandSourceStack> context, AtlasNetEntityStore store) {
        var source = context.getSource();
        long id = getTarget(source, StringArgumentType.getString(context, "entity_id"));
        return queue(id, () -> store.load(id).whenComplete((result, failure) -> {
            if (failure != null) report(source, "Entity read failed: " + failure.getMessage(), true);
            else if (result.isEmpty()) report(source, "No snapshot for entity " + Long.toUnsignedString(id), true);
            else {
                var snapshot = result.get().snapshot();
                report(source, "Entity " + Long.toUnsignedString(id) + " " + snapshot.entityType() +
                        " UUID " + snapshot.minecraftId() + " generation " + snapshot.ownershipGeneration() +
                        " revision " + snapshot.revision() + " effects " + snapshot.data().getList("active_effects", 10),
                        false);
            }
        }));
    }

    private int requestEffect(CommandContext<CommandSourceStack> context, Shard shard) {
        var source = context.getSource();
        long id = getTarget(source, StringArgumentType.getString(context, "entity_id"));
        var effect = ResourceLocationArgument.getId(context, "effect");
        if (BuiltInRegistries.MOB_EFFECT.getHolder(effect).isEmpty()) {
            source.sendFailure(Component.literal("Unknown vanilla effect"));
            return 0;
        }
        int ticks = IntegerArgumentType.getInteger(context, "ticks");
        int amplifier = IntegerArgumentType.getInteger(context, "amplifier");
        return queue(id, () -> applyEffect(shard, id, effect, ticks, amplifier).whenComplete((applied, failure) -> {
            if (failure != null) report(source, "EntityRPC failed: " + failure.getMessage(), true);
            else report(source, applied ? "Effect applied by owner" : "Owner did not change the effect", false);
        }));
    }

    private int scheduleProbe(CommandContext<CommandSourceStack> context, Shard shard, UUID shardId,
                              AtlasNetEntityStore store, LongUnaryOperator clientId) {
        var source = context.getSource();
        long id = getTarget(source, StringArgumentType.getString(context, "entity_id"));
        if (id == 0) return 0;
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        long client = clientId.applyAsLong(id);
        byte[] message = client == 0 ? null : encodeProbeMessage(shardId);
        after(seconds, () -> probe(shard, shardId, store, id, client, message));
        source.sendSuccess(() -> Component.literal("Entity probe scheduled on shard " + shardId
                + " in " + seconds + " seconds. The result will be in that shard's log."), false);
        return 1;
    }

    private void after(int seconds, Runnable action) {
        CompletableFuture.delayedExecutor(seconds, TimeUnit.SECONDS).execute(() -> commands.add(action));
    }

    private byte[] encodeProbeMessage(UUID shardId) {
        var protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(server.registryAccess()));
        var buffer = Unpooled.buffer();
        try {
            protocol.codec().encode(buffer, new ClientboundSystemChatPacket(
                    Component.literal("Remote entity probe passed. Direct Signal from shard " + shardId), false));
            var bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private void probe(Shard shard, UUID shardId, AtlasNetEntityStore store, long id,
                       long client, byte[] message) {
        if (server.submit(() -> localEntity.apply(id) != null).join()) {
            logProbe(shardId, id, "Target still belongs to the initiating shard");
            return;
        }
        store.load(id).thenCompose(result -> {
            var previous = result.orElseThrow();
            var first = previous.snapshot();
            var attempted = new AtlasNetEntityStore.Snapshot(id, first.ownershipGeneration(),
                    Math.addExact(first.revision(), 1), first.minecraftId(), first.entityType(),
                    first.dimension(), first.data());
            return store.publish(previous, attempted).handle((ignored, rejection) -> {
                if (rejection == null) throw new IllegalStateException("Remote store did not reject a direct write");
                return first;
            });
        }).thenCompose(first -> applyEffect(shard, id, PROBE_EFFECT, 12000, 0).thenApply(applied -> {
            if (!applied) throw new IllegalStateException("Owner did not apply Glowing");
            return first;
        })).whenComplete((first, failure) -> {
            if (failure != null) {
                logProbe(shardId, id, "FAILED " + failure.getMessage());
                return;
            }
            after(3, () -> store.load(id).whenComplete((read, error) -> {
                if (error != null || read.isEmpty()) {
                    logProbe(shardId, id, "FAILED to read the updated snapshot");
                    return;
                }
                var updated = read.get().snapshot();
                boolean glowing = false;
                for (var effect : updated.data().getList("active_effects", 10)) {
                    if (effect instanceof CompoundTag tag && PROBE_EFFECT.toString().equals(tag.getString("id"))) {
                        glowing = true;
                        break;
                    }
                }
                boolean advanced = updated.minecraftId().equals(first.minecraftId())
                        && updated.ownershipGeneration() == first.ownershipGeneration()
                        && updated.revision() > first.revision();
                logProbe(shardId, id, (advanced && glowing ? "PASS" : "FAILED")
                        + " type " + updated.entityType() + " generation " + updated.ownershipGeneration()
                        + " revisions " + first.revision() + " -> " + updated.revision()
                        + ", direct write rejected, Glowing " + glowing);
                if (advanced && glowing && message != null) {
                    try {
                        MinecraftShardBridge.dispatchPlayPacket(shard, client, id, message);
                        shard.flushSignals();
                        logProbe(shardId, id, "queued direct client Signal");
                    } catch (GErrorException signalFailure) {
                        LOG.warn("Could not send direct client Signal for entity probe", signalFailure);
                    }
                }
            }));
        });
    }

    private static void logProbe(UUID shard, long id, String result) {
        LOG.info("Entity probe shard {} entity {} {}", shard, Long.toUnsignedString(id), result);
    }

    private static long getTarget(CommandSourceStack source, String value) {
        try {
            long id = Long.parseUnsignedLong(value);
            if (id != 0) return id;
        } catch (NumberFormatException ignored) {
            // Give the operator the same message for malformed and zero IDs.
        }
        source.sendFailure(Component.literal("Expected a nonzero unsigned AtlasNet EntityID"));
        return 0;
    }

    private int queue(long id, Runnable command) {
        if (id == 0) return 0;
        commands.add(command);
        return 1;
    }

    private void report(CommandSourceStack source, String message, boolean failure) {
        server.execute(() -> {
            if (failure) source.sendFailure(Component.literal(message));
            else source.sendSuccess(() -> Component.literal(message), false);
        });
    }

    /** Invoke on the AtlasNet worker thread. The completion runs on its GLib context. */
    public static CompletableFuture<Boolean> applyEffect(Shard shard, long target, ResourceLocation effect,
                                                         int duration, int amplifier) {
        var response = new CompletableFuture<Boolean>();
        try {
            validate(effect, duration, amplifier);
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeByte(APPLY_EFFECT);
                output.writeUTF(effect.toString());
                output.writeInt(duration);
                output.writeByte(amplifier);
            }
            shard.callEntityRpcAsync(target, METHOD, bytes.toByteArray(), result -> {
                try {
                    var reply = shard.callEntityRpcFinish(result);
                    if (reply.length != 1 || reply[0] < 0 || reply[0] > 1)
                        throw new IllegalStateException("Invalid Minecraft entity operation response");
                    response.complete(reply[0] == 1);
                } catch (GErrorException | RuntimeException failure) {
                    response.completeExceptionally(failure);
                }
            });
        } catch (IOException | RuntimeException failure) {
            response.completeExceptionally(failure);
        }
        return response;
    }

    private record Effect(ResourceLocation id, int duration, int amplifier) {}

    private static Effect decode(byte[] bytes) throws IOException {
        if (bytes.length > 512) throw new IOException("Entity operation payload too large");
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readUnsignedByte() != APPLY_EFFECT) throw new IOException("Unknown Minecraft entity operation");
            var effect = ResourceLocation.parse(input.readUTF());
            var duration = input.readInt();
            var amplifier = input.readUnsignedByte();
            if (input.read() != -1) throw new IOException("Trailing Minecraft entity operation data");
            validate(effect, duration, amplifier);
            return new Effect(effect, duration, amplifier);
        }
    }

    private static void validate(ResourceLocation effect, int duration, int amplifier) {
        if (effect.toString().length() > 128 || duration <= 0 || duration > 72000 ||
                amplifier < 0 || amplifier > 255)
            throw new IllegalArgumentException("Invalid vanilla status effect parameters");
    }

    private byte[] apply(long id, Effect effect) {
        if (!(localEntity.apply(id) instanceof LivingEntity living) || living.isRemoved())
            throw new IllegalStateException("Entity has no active local living representation");
        var type = BuiltInRegistries.MOB_EFFECT.getHolder(effect.id()).orElseThrow();
        var applied = living.addEffect(new MobEffectInstance(type, effect.duration(), effect.amplifier()));
        if (applied) publication.capture(id, living);
        return new byte[]{(byte) (applied ? 1 : 0)};
    }
}
