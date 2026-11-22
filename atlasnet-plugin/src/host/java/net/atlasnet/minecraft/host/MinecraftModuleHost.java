package net.atlasnet.minecraft.host;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.atlasnet.minecraft.admission.MinecraftAdmissionDefaults;
import net.atlasnet.minecraft.admission.MinecraftAdmissionHandler;
import net.atlasnet.minecraft.endpoint.MinecraftTcpEndpoint;
import net.atlasnet.minecraft.server.MinecraftShardRuntime;
import net.atlasnet.minecraft.spawn.MinecraftSpawnResolver;
import org.atlasnet.atlasnet.Registry;
import org.atlasnet.atlasnet.Shard;

/** Entry point called by this project's native AtlasNet module. */
public final class MinecraftModuleHost {
    private MinecraftModuleHost() {}

    public static void register(long registryAddress, boolean worker, String worldDirectory) throws Exception {
        var registry = new Registry(MemorySegment.ofAddress(registryAddress));
        registry.registerShardProvider(() -> shard(Path.of(worldDirectory)));
        if (!worker) {
            var admission = new MinecraftAdmissionHandler(MinecraftAdmissionDefaults.CONFIG);
            admission.register(registry, "minecraft");
            MinecraftSpawnResolver.DEFAULT.register(registry, "minecraft");
            MinecraftTcpEndpoint.register(registry, "minecraft", "minecraft", "minecraft",
                    error -> {
                        System.getLogger(MinecraftModuleHost.class.getName()).log(
                                System.Logger.Level.ERROR, "Could not start Minecraft TCP ingress", error);
                        System.exit(1);
                    });
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(admission::close));
        }
    }

    private static Shard shard(Path directory) {
        var shard = new Shard();
        var runtime = new MinecraftShardRuntime[1];
        shard.onStart(id -> {
            try {
                runtime[0] = MinecraftShardRuntime.start(directory, shard);
                runtime[0].ready().get(90, TimeUnit.SECONDS);
            } catch (Exception failure) {
                System.getLogger(MinecraftModuleHost.class.getName()).log(
                        System.Logger.Level.ERROR, "Could not start Minecraft shard", failure);
                System.exit(1);
            }
        });
        shard.onShutdown(() -> {
            if (runtime[0] != null) runtime[0].close();
        });
        return shard;
    }
}
