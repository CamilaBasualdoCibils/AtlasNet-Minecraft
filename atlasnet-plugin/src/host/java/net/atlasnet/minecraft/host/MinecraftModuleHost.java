package net.atlasnet.minecraft.host;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.atlasnet.minecraft.admission.MinecraftAdmissionDefaults;
import net.atlasnet.minecraft.admission.MinecraftAdmissionHandler;
import net.atlasnet.minecraft.endpoint.MinecraftTcpEndpoint;
import net.atlasnet.minecraft.server.MinecraftChunkSubscriptions;
import net.atlasnet.minecraft.server.MinecraftShardRuntime;
import net.atlasnet.minecraft.spawn.MinecraftSpawnResolver;
import org.atlasnet.atlasnet.Ids;
import org.atlasnet.atlasnet.Registry;
import org.atlasnet.atlasnet.Shard;

/** Entry point called by this project's native AtlasNet module. */
public final class MinecraftModuleHost {
    private MinecraftModuleHost() {}

    public static void registerShard(long registryAddress, String worldDirectory) throws Exception {
        registry(registryAddress).registerShardProvider(() -> shard(Path.of(worldDirectory)));
    }

    public static void registerAdmission(long registryAddress, String worldDirectory) throws Exception {
        var admission = new MinecraftAdmissionHandler(MinecraftAdmissionDefaults.CONFIG);
        admission.register(registry(registryAddress), "minecraft");
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(admission::close));
    }

    public static void registerEndpoint(long registryAddress, String worldDirectory) throws Exception {
        MinecraftTcpEndpoint.register(registry(registryAddress), "minecraft", "minecraft", "minecraft",
                error -> {
                    System.getLogger(MinecraftModuleHost.class.getName()).log(
                            System.Logger.Level.ERROR, "Could not start Minecraft TCP ingress", error);
                    System.exit(1);
                });
    }

    public static void registerSpawn(long registryAddress, String worldDirectory) throws Exception {
        MinecraftSpawnResolver.DEFAULT.register(registry(registryAddress), "minecraft");
    }

    private static Registry registry(long address) {
        return new Registry(MemorySegment.ofAddress(address));
    }

    private static Shard shard(Path directory) {
        var shard = new Shard();
        var runtime = new MinecraftShardRuntime[1];
        var subscriptions = new MinecraftChunkSubscriptions[1];
        shard.onStart(id -> {
            try {
                subscriptions[0] = new MinecraftChunkSubscriptions(shard, Ids.uuid(id));
                runtime[0] = MinecraftShardRuntime.start(directory, shard, subscriptions[0]);
                runtime[0].ready().get(90, TimeUnit.SECONDS);
            } catch (Exception failure) {
                System.getLogger(MinecraftModuleHost.class.getName()).log(
                        System.Logger.Level.ERROR, "Could not start Minecraft shard", failure);
                System.exit(1);
            }
        });
        shard.onShutdown(() -> {
            if (runtime[0] != null) runtime[0].close();
            if (subscriptions[0] != null) subscriptions[0].close();
        });
        return shard;
    }
}
