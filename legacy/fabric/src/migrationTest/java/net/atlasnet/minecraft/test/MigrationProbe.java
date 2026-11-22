package net.atlasnet.minecraft.test;

import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import net.atlasnet.minecraft.fabric.client.AtlasNetPlayConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.atlasnet.minecraft.atlasnet.protocol.signals.PlayerPoseSignal;

/** Loaded only by -PatlasnetMigrationTest. All inputs use the real client game loop. */
public final class MigrationProbe {
    private static BufferedWriter output;
    private static BufferedWriter poses;
    private static boolean active;
    private static boolean walking;
    private static boolean dropSettled;
    private static final java.util.Set<Long> droppedRevisions = new java.util.HashSet<>();
    private static int direction = 1;
    private static int corrections;
    private static int snaps;
    private static int handoffs;
    private static long sample;
    private static long lastTick;
    private static double previousX;
    private static String previousShard;
    private static Object originalWorld;
    private static Object originalPlayer;

    public static void packet(byte[] payload) {
        var mc = Minecraft.getInstance();
        if (!active || mc.level == null) {
            return;
        }
        var raw = Unpooled.wrappedBuffer(payload);
        try {
            var protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                    RegistryFriendlyByteBuf.decorator(mc.level.registryAccess()));
            if (protocol.codec().decode(new RegistryFriendlyByteBuf(raw, mc.level.registryAccess()))
                    instanceof ClientboundPlayerPositionPacket) {
                corrections++;
            }
        } finally {
            raw.release();
        }
    }

    public static void handoffPosition(boolean preserved) {
        if (active && !preserved) {
            snaps++;
        }
    }

    public static boolean pose(int id, byte[] payload) {
        if (!active || walking || (id != PlayerPoseSignal.SHOW && id != PlayerPoseSignal.UPDATE)) {
            return false;
        }
        try {
            if (poses == null) {
                poses = Files.newBufferedWriter(Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("migration-poses.jsonl"));
            }
            var pose = PlayerPoseSignal.decode(payload);
            var row = new JsonObject();
            row.addProperty("time", System.currentTimeMillis());
            row.addProperty("x", pose.x());
            row.addProperty("vx", pose.motionX());
            row.addProperty("revision", pose.revision());
            row.addProperty("entity", pose.entity());
            poses.write(row.toString());
            poses.newLine();
            poses.flush();
            // Deliberately omit the first resting update of the walker, to
            // verify the reliable confirmation can recover a lost datagram.
            if (dropSettled && id == PlayerPoseSignal.UPDATE && Math.abs(pose.z()) < .001
                    && pose.motionX() == 0 && pose.motionY() == 0 && pose.motionZ() == 0) {
                if (droppedRevisions.add(pose.revision())) {
                    return true;
                }
                var recovered = new JsonObject();
                recovered.addProperty("recoveredRevision", pose.revision());
                recovered.addProperty("omittedUpdates", droppedRevisions.size());
                Files.writeString(Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("migration-settled.json"), recovered.toString());
                dropSettled = false;
            }
            return false;
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    public static void tick(Minecraft mc) {
        var connection = AtlasNetPlayConnection.current();
        if (connection == null || mc.player == null || mc.level == null) {
            return;
        }
        try {
            if (output == null) {
                output = Files.newBufferedWriter(mc.gameDirectory.toPath().resolve("migration-samples.jsonl"));
            }
            var command = mc.gameDirectory.toPath().resolve("migration-command");
            if (Files.isRegularFile(command)) {
                String action = Files.readString(command).strip();
                Files.delete(command);
                if (action.equals("start-walker") || action.equals("start-observer")) {
                    active = true;
                    walking = action.equals("start-walker");
                    if (!walking) {
                        connection.command("atlasnet client showRegion true");
                    }
                    corrections = handoffs = snaps = 0;
                    sample = 0;
                    previousShard = connection.visibleShardLabel();
                    originalWorld = mc.level;
                    originalPlayer = mc.player;
                    previousX = mc.player.getX();
                } else if (action.equals("stop")) {
                    active = walking = false;
                    mc.options.keyUp.setDown(false);
                    mc.options.keySprint.setDown(false);
                } else if (action.equals("capture")) {
                    Screenshot.grab(mc.gameDirectory, "migration.png", mc.getMainRenderTarget(), ignored -> {});
                } else if (action.equals("drop-settled")) {
                    dropSettled = true;
                    Files.writeString(mc.gameDirectory.toPath().resolve("migration-drop-ready"), "ready");
                }
            }
            var remote = mc.level.players().stream().filter(player -> player != mc.player)
                    .findFirst().orElse(null);
            if (walking) {
                if (mc.player.getX() >= 32) {
                    direction = -1;
                } else if (mc.player.getX() <= -32) {
                    direction = 1;
                }
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(false);
                mc.player.setYRot(direction > 0 ? -90 : 90);
                mc.player.setXRot(0);
            } else if (active && remote != null) {
                // Keep the stationary observer actually looking at the walker.
                double dx = remote.getX() - mc.player.getX();
                double dz = remote.getZ() - mc.player.getZ();
                mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
                mc.player.setXRot(4);
            }
            String shard = connection.visibleShardLabel();
            if (active && previousShard != null && !previousShard.equals(shard)) {
                handoffs++;
            }
            long now = System.nanoTime();
            var row = new JsonObject();
            row.addProperty("time", System.currentTimeMillis());
            row.addProperty("tickGapMs", lastTick == 0 ? 0 : (now - lastTick) / 1e6);
            row.addProperty("active", active);
            row.addProperty("sample", sample++);
            row.addProperty("shard", shard);
            row.addProperty("showRegion", connection.showsRegion());
            row.addProperty("x", mc.player.getX());
            row.addProperty("y", mc.player.getY());
            row.addProperty("z", mc.player.getZ());
            row.addProperty("step", mc.player.getX() - previousX);
            row.addProperty("direction", direction);
            row.addProperty("handoffs", handoffs);
            row.addProperty("positionPackets", corrections);
            row.addProperty("handoffCorrections", snaps);
            row.addProperty("loading", mc.screen instanceof ReceivingLevelScreen);
            row.addProperty("screen", mc.screen == null ? "" : mc.screen.getClass().getSimpleName());
            row.addProperty("sameWorld", originalWorld == null || originalWorld == mc.level);
            row.addProperty("samePlayer", originalPlayer == null || originalPlayer == mc.player);
            row.addProperty("remoteCount", mc.level.players().size() - 1);
            row.addProperty("remoteId", remote == null ? 0 : remote.getId());
            row.addProperty("remoteX", remote == null ? 0 : remote.getX());
            row.addProperty("grounded", mc.player.onGround());
            row.addProperty("health", mc.player.getHealth());
            output.write(row.toString());
            output.newLine();
            output.flush();
            previousShard = shard;
            previousX = mc.player.getX();
            lastTick = now;
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
}
