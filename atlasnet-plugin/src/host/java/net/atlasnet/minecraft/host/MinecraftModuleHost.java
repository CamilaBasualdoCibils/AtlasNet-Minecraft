package net.atlasnet.minecraft.host;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.atlasnet.minecraft.host.terrain.TerrainMutationHooks;
import net.atlasnet.minecraft.host.terrain.ChunkTerrainReplication;
import net.atlasnet.minecraft.host.terrain.AuthoritativeTerrainSaves;
import net.atlasnet.minecraft.host.terrain.ReplicaTerrainRefresh;
import net.atlasnet.minecraft.server.terrain.PostgresTerrainStore;
import net.atlasnet.minecraft.server.terrain.TerrainStore;
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
    static {
        // Initialize Java-GI before its GObject cleaner can initialize MainContext concurrently.
        org.gnome.glib.GLib.getMonotonicTime();
    }

    private MinecraftModuleHost() {}

    public static void registerShard(long registryAddress, String worldDirectory) throws Exception {
        var registry = registry(registryAddress);
        var settings = MinecraftServerProperties.read(registry.getClusterApplicationConfigJson());
        MinecraftServerProperties.vanilla(settings);
        var application = registry.getClusterApplicationConfigJson();
        var interval = MinecraftServerProperties.checkpointInterval(application);
        var minecraft = com.google.gson.JsonParser.parseString(application).getAsJsonObject()
                .getAsJsonObject("minecraft");
        boolean terrainEnabled = minecraft != null && minecraft.has("terrain-enabled")
                && minecraft.get("terrain-enabled").getAsBoolean();
        registry.registerShardProvider(() -> shard(Path.of(worldDirectory), settings, interval, terrainEnabled));
    }

    public static void registerAdmission(long registryAddress, String worldDirectory) throws Exception {
        var registry = registry(registryAddress);
        var settings = MinecraftServerProperties.vanilla(
                MinecraftServerProperties.read(registry.getClusterApplicationConfigJson()));
        var checkpoints = checkpoints(settings.levelName);
        var admission = new MinecraftAdmissionHandler(new MinecraftAdmissionConfig(
                MinecraftAdmissionDefaults.CONFIG.brand(), settings.networkCompressionThreshold,
                settings.functionPermissionLevel), settings.onlineMode, uuid -> {
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
        registry(registryAddress).registerSpawnResolver("minecraft",
                org.atlasnet.atlasnet.Callbacks.spawnResolver((client, data) -> {
                    var saved = net.atlasnet.minecraft.server.MinecraftPlayerJoin.checkpointSpawn(data);
                    var resolver = saved.map(spawn -> new MinecraftSpawnResolver(spawn.dimension().toString(),
                            spawn.x(), spawn.y(), spawn.z())).orElse(MinecraftSpawnResolver.DEFAULT);
                    return resolver.run(client, data);
                }));
    }

    private static Registry registry(long address) {
        return new Registry(MemorySegment.ofAddress(address));
    }

    private static Shard shard(Path directory, java.util.Properties settings, Duration interval, boolean terrainEnabled) {
        var shard = new Shard();
        var runtime = new MinecraftShardRuntime[1];
        var workerDirectory = new MinecraftWorkerDirectory[1];
        var subscriptions = new MinecraftChunkSubscriptions[1];
        var ordinaryAuthority = new OrdinaryEntityAuthority[1];
        var stopped = new java.util.concurrent.atomic.AtomicBoolean();
        shard.onStart(id -> {
            try {
                workerDirectory[0] = MinecraftWorkerDirectory.acquire(directory);
                var world = settings.getProperty("level-name", "world");
                var gameServer = new net.minecraft.server.MinecraftServer[1];
                var terrainSaves = new java.util.HashMap<net.minecraft.resources.ResourceLocation,
                        AuthoritativeTerrainSaves>();
                var terrainReference = new ChunkTerrainAuthority[1];
                var terrainStore = terrainEnabled ? new PostgresTerrainStore(database()) : null;
                var terrainIo = terrainEnabled ? java.util.concurrent.Executors.newFixedThreadPool(4) : null;
                var refresh = terrainEnabled ? new ReplicaTerrainRefresh() : null;
                var replication = terrainEnabled ? new ChunkTerrainReplication(shard, Ids.uuid(id), world,
                        terrainStore, terrainIo, (key, snapshot, current) -> {
                            var server = gameServer[0];
                            if (server == null) return java.util.concurrent.CompletableFuture.failedFuture(
                                    new IllegalStateException("Minecraft is not ready to refresh terrain"));
                            return server.submit(() -> {
                                if (current.getAsBoolean())
                                    refresh.apply(server, terrainReference[0].backend(), key, snapshot);
                            });
                        }) : null;
                var terrain = terrainEnabled ? new ChunkTerrainAuthority(shard, terrainStore, terrainIo,
                        replication::committed, (key, snapshot) -> {
                            var server = gameServer[0];
                            if (server == null) return java.util.concurrent.CompletableFuture.failedFuture(
                                    new IllegalStateException("Minecraft is not ready to activate terrain"));
                            return server.submit(() -> refresh.activate(server, terrainReference[0].backend(),
                                    key, snapshot));
                        }, (key, fence) -> {
                            var server = gameServer[0];
                            return server.submit(() -> {
                                var saves = terrainSaves.get(key.dimension());
                                if (saves != null) saves.flush(new net.minecraft.world.level.ChunkPos(key.x(), key.z()));
                                return terrainReference[0].backend().freeze(key, fence);
                            }).join();
                        }) : null;
                terrainReference[0] = terrain;
                subscriptions[0] = new MinecraftChunkSubscriptions(shard, Ids.uuid(id), (dimension, x, z, loaded) -> {
                    if (terrain == null) return;
                    var key = new TerrainStore.ChunkKey(world, dimension, x, z);
                    replication.loaded(key, loaded);
                    if (!loaded) return;
                    terrain.registerLoaded(key).whenComplete((entity, failure) -> {
                        if (failure != null) {
                            System.getLogger(MinecraftModuleHost.class.getName()).log(
                                    System.Logger.Level.WARNING, "Could not activate terrain " + key, failure);
                            return;
                        }
                        if (entity.isEmpty()) return;
                        var server = gameServer[0];
                        if (server == null || !terrain.backend().maySave(key)) return;
                        server.execute(() -> {
                            var dimensionKey = net.minecraft.resources.ResourceKey.create(
                                    net.minecraft.core.registries.Registries.DIMENSION, dimension);
                            var level = server.getLevel(dimensionKey);
                            if (level == null) return;
                            var chunk = level.getChunkSource().getChunkNow(x, z);
                            if (chunk != null && terrain.backend().needsInitialSnapshot(key)) {
                                var terrainNbt = net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk);
                                level.getChunkSource().chunkMap.write(chunk.getPos(), terrainNbt)
                                        .whenComplete((saved, error) -> {
                                            if (error != null) server.execute(() -> chunk.setUnsaved(true));
                                        });
                            }
                            terrain.routeInitialPlacement(key, entity.getAsLong());
                        });
                    });
                });
                var checkpoints = checkpoints(world);
                var publication = new PlayerEntityPublication(shard, checkpoints, interval);
                var ordinary = new OrdinaryEntityAuthority(shard, publication);
                ordinaryAuthority[0] = ordinary;
                var store = new AtlasNetEntityStore(shard);
                var operations = new VanillaEntityOperations[1];
                var playerBridge = new MinecraftShardBridge[1];
                runtime[0] = MinecraftShardRuntime.start(workerDirectory[0].path(), shard, subscriptions[0], settings,
                        (server, worker) -> {
                            gameServer[0] = server;
                            if (terrain != null) {
                                for (var level : server.getAllLevels()) {
                                    var dimension = level.dimension().location();
                                    var saves = new AuthoritativeTerrainSaves(level, world, terrain.backend());
                                    terrainSaves.put(dimension, saves);
                                    TerrainMutationHooks.install(level, new TerrainMutationHooks.Policy() {
                                        @Override public void blockChanged(net.minecraft.core.BlockPos position) {
                                            saves.changed(new net.minecraft.world.level.ChunkPos(position));
                                        }

                                        @Override public boolean mayTick(net.minecraft.world.level.ChunkPos chunk) {
                                            return terrain.backend().maySave(new TerrainStore.ChunkKey(
                                                    world, dimension, chunk.x, chunk.z));
                                        }

                                        @Override public boolean maySetBlock(net.minecraft.core.BlockPos position,
                                                net.minecraft.world.level.block.state.BlockState state,
                                                int flags, int updateLimit) {
                                            return mayTick(new net.minecraft.world.level.ChunkPos(position));
                                        }
                                    });
                                }
                            }
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
                        }, ordinary, terrain == null ? null : terrain.backend());
                runtime[0].ready().get(90, TimeUnit.SECONDS);
                int viewRadius = MinecraftServerProperties.vanilla(settings).viewDistance * 16;
                playerBridge[0].setPlayerActivityRadius(viewRadius);
                new net.atlasnet.minecraft.host.entity.MinecraftPlayerVisibility(gameServer[0], playerBridge[0],
                        shard, store, viewRadius);
                if (terrain != null) {
                    var blocks = new net.atlasnet.minecraft.host.inventory.AtlasNetBlockEntity(
                            world, gameServer[0], terrain);
                    new net.atlasnet.minecraft.host.inventory.AtlasNetInventory(gameServer[0], shard, blocks,
                            entityId -> {
                                var player = playerBridge[0].localPlayer(entityId);
                                return player == null ? ordinary.findLocal(entityId) : player;
                            });
                    new net.atlasnet.minecraft.host.terrain.RemoteCreativeInteraction(gameServer[0], playerBridge[0],
                            shard, world, terrain.backend(), terrainSaves, publication);
                }
                operations[0].register(shard);
                ordinary.registerTransfers(gameServer[0], playerBridge[0], terrain);
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
        return new PostgresEntityCheckpoints(database(), world);
    }

    private static PGSimpleDataSource database() {
        var database = new PGSimpleDataSource();
        database.setPortNumbers(new int[]{Integer.parseInt(environment("ATLASNET_POSTGRES_PORT", "45432"))});
        database.setDatabaseName(environment("ATLASNET_POSTGRES_DATABASE", "minecraft"));
        database.setUser(environment("ATLASNET_POSTGRES_USER", "minecraft"));
        database.setPassword(environment("ATLASNET_POSTGRES_PASSWORD", "minecraft-dev"));
        database.setConnectTimeout(3);
        database.setSocketTimeout(3);
        return database;
    }

    private static String environment(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
