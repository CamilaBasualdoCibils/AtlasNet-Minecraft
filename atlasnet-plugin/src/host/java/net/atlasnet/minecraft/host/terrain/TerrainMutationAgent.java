package net.atlasnet.minecraft.host.terrain;


import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Inserts entry guards into version-pinned vanilla methods before their classes load. */
public final class TerrainMutationAgent {
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String TRACKED_ENTITY = "net/minecraft/server/level/ChunkMap$TrackedEntity";
    private static final String PLAYER_LIST = "net/minecraft/server/players/PlayerList";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String LEVEL_TICKS = "net/minecraft/world/ticks/LevelTicks";
    private static final String SCHEDULED_TICK = "net/minecraft/world/ticks/ScheduledTick";
    private static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    private static final String BLOCK_ENTITY_TICKER =
            "net/minecraft/world/level/chunk/LevelChunk$BoundTickingBlockEntity";
    private static final ClassDesc HOOKS = ClassDesc.of(
            "net.atlasnet.minecraft.host.terrain.TerrainMutationHooks");
    private static final Set<String> transformed = ConcurrentHashMap.newKeySet();
    private static final System.Logger LOG = System.getLogger(TerrainMutationAgent.class.getName());

    private TerrainMutationAgent() {}

    public static void premain(String options, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> definedClass,
                                    ProtectionDomain domain, byte[] bytes) {
                if (!LEVEL.equals(name) && !SERVER_LEVEL.equals(name)
                        && !LEVEL_TICKS.equals(name) && !BLOCK_ENTITY.equals(name)
                        && !BLOCK_ENTITY_TICKER.equals(name) && !TRACKED_ENTITY.equals(name)
                        && !PLAYER_LIST.equals(name)) return null;
                try {
                    var output = addGuards(name, bytes);
                    transformed.add(name);
                    return output;
                } catch (RuntimeException failure) {
                    LOG.log(System.Logger.Level.ERROR, "Cannot apply terrain hooks to " + name, failure);
                    return null;
                }
            }
        });
    }

    static void requireHooks() {
        if (!transformed.contains(LEVEL) || !transformed.contains(SERVER_LEVEL)
                || !transformed.contains(LEVEL_TICKS)) {
            throw new IllegalStateException("Minecraft 1.21.1 terrain mutation hooks are unavailable");
        }
    }

    private static byte[] addGuards(String name, byte[] bytes) {
        var file = ClassFile.of();
        var original = file.parse(bytes);
        if (PLAYER_LIST.equals(name)) {
            var args = "Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;"
                    + "Lnet/minecraft/network/chat/ChatType$Bound;";
            return file.transformClass(original, guard("broadcastChatMessage", "(" + args + ")V",
                    "mayBroadcastLocalChat", "(" + args + ")Z", true,
                    builder -> builder.aload(1).aload(2).aload(3)));
        }
        if (TRACKED_ENTITY.equals(name)) {
            return file.transformClass(original, guard("updatePlayer", "(Lnet/minecraft/server/level/ServerPlayer;)V",
                    "mayTrackEntity", "(L" + ENTITY + ";)Z", true,
                    builder -> builder.aload(0).getfield(ClassDesc.ofInternalName(TRACKED_ENTITY),
                            "entity", ClassDesc.ofInternalName(ENTITY))));
        }
        if (LEVEL_TICKS.equals(name)) {
            return file.transformClass(original, afterVoid("schedule", "(L" + SCHEDULED_TICK + ";)V",
                    "scheduledTickAdded", "(L" + LEVEL_TICKS + ";L" + SCHEDULED_TICK + ";)V"));
        }
        if (BLOCK_ENTITY_TICKER.equals(name)) {
            return file.transformClass(original, guard("tick", "()V",
                    "mayTickBlockEntity", "(L" + BLOCK_ENTITY + ";)Z", true,
                    builder -> builder.aload(0).getfield(ClassDesc.ofInternalName(BLOCK_ENTITY_TICKER),
                            "blockEntity", ClassDesc.ofInternalName(BLOCK_ENTITY))));
        }
        if (BLOCK_ENTITY.equals(name)) {
            return file.transformClass(original, ClassTransform.transformingMethodBodies(
                    method -> method.methodName().equalsString("setChanged") && method.methodType().equalsString(
                            "(L" + LEVEL + ";Lnet/minecraft/core/BlockPos;"
                                    + "Lnet/minecraft/world/level/block/state/BlockState;)V"),
                    new CodeTransform() {
                        @Override public void accept(CodeBuilder builder, CodeElement element) {
                            if (element instanceof java.lang.classfile.instruction.ReturnInstruction) {
                                builder.aload(0).aload(1).invokestatic(HOOKS, "blockEntityChanged",
                                        MethodTypeDesc.ofDescriptor("(L" + LEVEL + ";Lnet/minecraft/core/BlockPos;)V"));
                            }
                            builder.with(element);
                        }
                    }));
        }
        if (LEVEL.equals(name)) {
            return file.transformClass(original, guard("setBlock",
                    "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
                    "maySetBlock", "(L" + LEVEL + ";Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;II)Z", false,
                    builder -> builder.aload(0).aload(1).aload(2).iload(3).iload(4)));
        }
        var scheduled = file.transformClass(original,
                guard("shouldTickBlocksAt", "(J)Z", "mayTickScheduled", "(L" + SERVER_LEVEL + ";J)Z", false,
                        builder -> builder.aload(0).lload(1)));
        var random = file.transformClass(file.parse(scheduled),
                guard("tickChunk", "(Lnet/minecraft/world/level/chunk/LevelChunk;I)V",
                        "mayTickChunk", "(L" + SERVER_LEVEL + ";Lnet/minecraft/world/level/chunk/LevelChunk;)Z",
                        true, builder -> builder.aload(0).aload(1)));
        var consumedDescriptor = "(L" + SERVER_LEVEL + ";Lnet/minecraft/core/BlockPos;)V";
        var blocks = file.transformClass(file.parse(random), afterVoid("tickBlock",
                "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V",
                "scheduledTickConsumed", consumedDescriptor));
        return file.transformClass(file.parse(blocks), afterVoid("tickFluid",
                "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/Fluid;)V",
                "scheduledTickConsumed", consumedDescriptor));
    }

    private static ClassTransform afterVoid(String method, String descriptor, String hook, String hookDescriptor) {
        return ClassTransform.transformingMethodBodies(
                model -> model.methodName().equalsString(method) && model.methodType().equalsString(descriptor),
                (builder, element) -> {
                    if (element instanceof java.lang.classfile.instruction.ReturnInstruction) {
                        builder.aload(0).aload(1).invokestatic(HOOKS, hook,
                                MethodTypeDesc.ofDescriptor(hookDescriptor));
                    }
                    builder.with(element);
                });
    }

    private static ClassTransform guard(String method, String descriptor, String hook, String hookDescriptor,
                                        boolean returnsVoid, java.util.function.Consumer<CodeBuilder> arguments) {
        return ClassTransform.transformingMethodBodies(
                model -> model.methodName().equalsString(method) && model.methodType().equalsString(descriptor),
                new CodeTransform() {
                    @Override public void atStart(CodeBuilder builder) {
                        var proceed = builder.newLabel();
                        arguments.accept(builder);
                        builder.invokestatic(HOOKS, hook, MethodTypeDesc.ofDescriptor(hookDescriptor)).ifne(proceed);
                        if (returnsVoid) builder.return_();
                        else builder.iconst_0().ireturn();
                        builder.labelBinding(proceed);
                    }

                    @Override public void accept(CodeBuilder builder, CodeElement element) {
                        if ("setBlock".equals(method) && element instanceof java.lang.classfile.instruction.ReturnInstruction ret
                                && ret.opcode() == java.lang.classfile.Opcode.IRETURN) {
                            var done = builder.newLabel();
                            builder.dup().ifeq(done).aload(0).aload(1)
                                    .invokestatic(HOOKS, "blockChanged", MethodTypeDesc.ofDescriptor(
                                            "(L" + LEVEL + ";Lnet/minecraft/core/BlockPos;)V"))
                                    .labelBinding(done);
                        }
                        builder.with(element);
                    }
                });
    }
}
