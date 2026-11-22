package net.atlasnet.minecraft.fabric.server.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Selects the shared Overworld before Minecraft reads its dedicated server settings. */
public final class ShardWorldBootstrap implements PreLaunchEntrypoint {
    private static final String FLAT_SETTINGS = """
            {"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},
            {"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}]}""";

    @Override
    public void onPreLaunch() {
        var shared = System.getProperty("atlasnet.minecraft.shardProperties");
        if (shared == null) {
            return;
        }
        try {
            var preset = ValkeyChunkStorage.terrainPreset();
            var world = Boolean.getBoolean("atlasnet.minecraft.valkeyTerrain")
                    ? ValkeyChunkStorage.local().loadOrCreateWorld(preset) : null;
            configureShard(Path.of(shared), Path.of("server.properties").toAbsolutePath(), preset, world);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot initialize Minecraft shard settings", error);
        }
    }

    static void configureShard(Path shared, Path runtime, String preset, ValkeyChunkStorage.World world) throws IOException {
        var properties = new Properties();
        try (var input = Files.newInputStream(shared)) {
            properties.load(input);
        }
        properties.setProperty("level-type", "minecraft:" + preset);
        if (preset.equals("flat")) {
            properties.setProperty("generator-settings", FLAT_SETTINGS);
        }
        if (world != null) {
            properties.setProperty("level-seed", Long.toString(world.seed()));
            properties.setProperty("level-name", world.levelName());
        }
        Path pending = Files.createTempFile(runtime.getParent(), "server", ".properties");
        try {
            try (var output = Files.newOutputStream(pending)) {
                properties.store(output, "Minecraft server properties");
            }
            Files.move(pending, runtime, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
    }
}
