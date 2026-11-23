package net.atlasnet.minecraft.host;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.atlasnet.minecraft.admission.MinecraftAdmissionConfig;
import net.atlasnet.minecraft.admission.MinecraftAdmissionDefaults;
import net.atlasnet.minecraft.admission.MinecraftAdmissionHandler;
import net.atlasnet.minecraft.endpoint.MinecraftTcpEndpoint;
import net.atlasnet.minecraft.host.entity.PlayerEntityPublication;
import net.atlasnet.minecraft.host.entity.OrdinaryEntityAuthority;
import net.atlasnet.minecraft.host.entity.AtlasNetEntityStore;
import net.atlasnet.minecraft.host.entity.VanillaEntityOperations;
import net.atlasnet.minecraft.host.entity.PostgresEntityCheckpoints;
import java.time.Duration;
import org.postgresql.ds.PGSimpleDataSource;
import net.atlasnet.minecraft.server.MinecraftChunkSubscriptions;
import net.atlasnet.minecraft.server.MinecraftShardBridge;
import net.atlasnet.minecraft.server.MinecraftShardRuntime;
import net.atlasnet.minecraft.spawn.MinecraftSpawnResolver;
import org.atlasnet.atlasnet.Ids;
import org.atlasnet.atlasnet.Registry;
import org.atlasnet.atlasnet.Shard;

/** Entry point called by this project's native AtlasNet module. */
public final class MinecraftModuleHost {
    private MinecraftModuleHost() {}

    public static void registerShard(long registryAddress, String worldDirectory) throws Exception {
        var registry = registry(registryAddress);
        var settings = MinecraftServerProperties.read(registry.getClusterApplicationConfigJson());
        MinecraftServerProperties.vanilla(settings);
        var interval = MinecraftServerProperties.checkpointInterval(registry.getClusterApplicationConfigJson());
        registry.registerShardProvider(() -> shard(Path.of(worldDirectory), settings, interval));
    }

    public static void registerAdmission(long registryAddress, String worldDirectory) throws Exception {
        var registry = registry(registryAddress);
        var settings = MinecraftServerProperties.vanilla(
                MinecraftServerProperties.read(registry.getClusterApplicationConfigJson()));
        var checkpoints = checkpoints(settings.levelName);
        var admission = new MinecraftAdmissionHandler(new MinecraftAdmissionConfig(
                MinecraftAdmissionDefaults.CONFIG.brand(), settings.networkCompressionThreshold,
                settings.functionPermissionLevel), uuid -> {
                    try {
                        var snapshot = checkpoints.load(uuid).flatMap(PostgresEntityCheckpoints.Checkpoint::snapshot);
                        if (snapshot.isEmpty()) return new byte[0];
                        var output = new java.io.ByteArrayOutputStream();
                        net.minecraft.nbt.NbtIo.writeCompressed(snapshot.get().data(), output);
                        return output.toByteArray();
                    } catch (java.io.IOException | java.sql.SQLException failure) {
                        throw new IllegalStateException("Could not read authenticated player's checkpoint", failure);
                    }
                });
        admission.register(registry, "minecraft");
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

    private static Shard shard(Path directory, java.util.Properties settings, Duration interval) {
        var shard = new Shard();
        var runtime = new MinecraftShardRuntime[1];
        var workerDirectory = new MinecraftWorkerDirectory[1];
        var subscriptions = new MinecraftChunkSubscriptions[1];
        var ordinaryAuthority = new OrdinaryEntityAuthority[1];
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        shard.onStart(id -> {
            try {
                workerDirectory[0] = MinecraftWorkerDirectory.acquire(directory);
                subscriptions[0] = new MinecraftChunkSubscriptions(shard, Ids.uuid(id));
                var checkpoints = checkpoints(settings.getProperty("level-name", "world"));
                var publication = new PlayerEntityPublication(shard, checkpoints, interval);
                var ordinary = new OrdinaryEntityAuthority(shard, publication);
                ordinaryAuthority[0] = ordinary;
                var store = new AtlasNetEntityStore(shard);
                var operations = new VanillaEntityOperations[1];
                var gameServer = new net.minecraft.server.MinecraftServer[1];
                var playerBridge = new MinecraftShardBridge[1];
                runtime[0] = MinecraftShardRuntime.start(workerDirectory[0].path(), shard, subscriptions[0], settings,
                        (server, worker) -> {
                            gameServer[0] = server;
                            var bridge = new MinecraftShardBridge(server, worker,
                                    publication::capture, publication::retire);
                            playerBridge[0] = bridge;
                            operations[0] = new VanillaEntityOperations(server, entityId -> {
                                var player = bridge.localPlayer(entityId);
                                return player == null ? ordinary.findLocal(entityId) : player;
                            }, publication);
                            operations[0].registerCommands(shard, Ids.uuid(id), store, entity ->
                                    entity instanceof net.minecraft.server.level.ServerPlayer player
                                            ? bridge.localId(player) : ordinary.localId(entity), bridge::localClient);
                            return bridge;
                        }, ordinary);
                runtime[0].ready().get(90, TimeUnit.SECONDS);
                operations[0].register(shard);
                ordinary.registerTransfers(gameServer[0], playerBridge[0]);
            } catch (Exception failure) {
                System.getLogger(MinecraftModuleHost.class.getName()).log(
                        System.Logger.Level.ERROR, "Could not start Minecraft shard", failure);
                System.exit(1);
            }
        });
        shard.onShutdown(() -> {
            if (runtime[0] == null) stopped.set(true);
            else runtime[0].stopAsync().whenComplete((ignored, failure) -> stopped.set(true));
        });
        shard.onPollEvents(() -> {
            if (!stopped.compareAndSet(true, false)) return;
            try {
                if (subscriptions[0] != null) subscriptions[0].close();
                if (ordinaryAuthority[0] != null) ordinaryAuthority[0].finishShutdown();
            } finally {
                try {
                    if (workerDirectory[0] != null) workerDirectory[0].close();
                } catch (java.io.IOException failure) {
                    System.getLogger(MinecraftModuleHost.class.getName()).log(
                            System.Logger.Level.WARNING, "Could not release Minecraft worker directory", failure);
                }
            }
        });
        return shard;
    }
    private static PostgresEntityCheckpoints checkpoints(String world) throws java.sql.SQLException {
        var database = new PGSimpleDataSource();
        database.setPortNumbers(new int[]{Integer.parseInt(environment("ATLASNET_POSTGRES_PORT", "45432"))});
        database.setDatabaseName(environment("ATLASNET_POSTGRES_DATABASE", "minecraft"));
        database.setUser(environment("ATLASNET_POSTGRES_USER", "minecraft"));
        database.setPassword(environment("ATLASNET_POSTGRES_PASSWORD", "minecraft-dev"));
        database.setConnectTimeout(3);
        database.setSocketTimeout(3);
        return new PostgresEntityCheckpoints(database, world);
    }

    private static String environment(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
