package net.atlasnet.minecraft.server;

import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.Eula;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerSettings;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.atlasnet.atlasnet.Shard;

/** Starts the vanilla dedicated server with AtlasNet as its connection transport. */
public final class MinecraftShardRuntime implements AutoCloseable {
    private final HostedServer server;
    private final CompletableFuture<Void> ready;

    private MinecraftShardRuntime(HostedServer server, CompletableFuture<Void> ready) {
        this.server = server;
        this.ready = ready;
    }

    public static MinecraftShardRuntime start(Path directory, Shard shard) throws IOException {
        return start(directory, shard, null);
    }

    public static MinecraftShardRuntime start(Path directory, Shard shard,
                                              MinecraftChunkSubscriptions subscriptions) throws IOException {
        return start(directory, shard, subscriptions, new java.util.Properties());
    }

    public static MinecraftShardRuntime start(Path directory, Shard shard,
                                              MinecraftChunkSubscriptions subscriptions,
                                              java.util.Properties properties) throws IOException {
        return start(directory, shard, subscriptions, properties, MinecraftShardBridge::new);
    }

    public static MinecraftShardRuntime start(Path directory, Shard shard,
                                              MinecraftChunkSubscriptions subscriptions,
                                              java.util.Properties properties,
                                              java.util.function.BiFunction<MinecraftServer, Shard,
                                                      MinecraftShardBridge> bridgeFactory) throws IOException {
        return start(directory, shard, subscriptions, properties, bridgeFactory, null);
    }

    public static MinecraftShardRuntime start(Path directory, Shard shard,
                                              MinecraftChunkSubscriptions subscriptions,
                                              java.util.Properties properties,
                                              java.util.function.BiFunction<MinecraftServer, Shard,
                                                      MinecraftShardBridge> bridgeFactory,
                                              MinecraftEntityLifecycle.Observer entityObserver) throws IOException {
        Files.createDirectories(directory);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (!new Eula(directory.resolve("eula.txt")).hasAgreedToEULA()) {
            throw new IOException("Minecraft requires EULA acceptance in " + directory.resolve("eula.txt"));
        }
        var source = Files.createTempFile("atlasnet-minecraft-", ".properties");
        DedicatedServerSettings settings;
        try {
            try (var output = Files.newOutputStream(source)) {
                properties.store(output, null);
            }
            settings = new DedicatedServerSettings(source) {
                @Override
                public void forceSave() {
                    // Cluster configuration is read-only. Keep vanilla runtime changes in memory.
                }
            };
        } finally {
            Files.deleteIfExists(source);
        }
        var props = settings.getProperties();
        var storage = LevelStorageSource.createDefault(directory).createAccess(props.levelName);
        try {
            var packs = ServerPacksSource.createPackRepository(storage);
            var saved = storage.hasWorldData() ? storage.getDataTag() : null;
            var data = saved == null
                    ? new WorldDataConfiguration(props.initialDataPackConfiguration, FeatureFlags.DEFAULT_FLAGS)
                    : LevelStorageSource.readDataConfig(saved);
            var packConfig = new WorldLoader.PackConfig(packs, data, false, saved == null);
            var init = new WorldLoader.InitConfig(packConfig, Commands.CommandSelection.DEDICATED,
                    props.functionPermissionLevel);
            var world = WorldLoader.load(init, context -> {
                var dimensions = context.datapackDimensions().registryOrThrow(Registries.LEVEL_STEM);
                if (saved != null) {
                    var existing = LevelStorageSource.getLevelDataAndDimensions(saved, context.dataConfiguration(),
                            dimensions, context.datapackWorldgen());
                    return new WorldLoader.DataLoadOutput<>(existing.worldData(),
                            existing.dimensions().dimensionsRegistryAccess());
                }
                var baked = props.createDimensions(context.datapackWorldgen()).bake(dimensions);
                var levelSettings = new LevelSettings(props.levelName, props.gamemode, props.hardcore,
                        props.difficulty, false, new GameRules(), context.dataConfiguration());
                var worldData = new PrimaryLevelData(levelSettings, props.worldOptions, baked.specialWorldProperty(),
                        baked.lifecycle().add(context.datapackWorldgen().allRegistriesLifecycle()));
                return new WorldLoader.DataLoadOutput<>(worldData, baked.dimensionsRegistryAccess());
            }, WorldStem::new, Runnable::run, Runnable::run).join();
            try {
                var services = Services.create(new YggdrasilAuthenticationService(Proxy.NO_PROXY), directory.toFile());
                var ready = new CompletableFuture<Void>();
                var server = MinecraftServer.spin(thread -> new HostedServer(thread, storage, packs, world,
                        settings, services, shard, subscriptions, ready, bridgeFactory, entityObserver));
                return new MinecraftShardRuntime(server, ready);
            } catch (RuntimeException failure) {
                world.close();
                throw failure;
            }
        } catch (RuntimeException | IOException failure) {
            storage.close();
            throw failure;
        }
    }

    public CompletableFuture<Void> ready() {
        return ready;
    }

    /** Request a vanilla stop and let the calling AtlasNet worker keep polling. */
    public CompletableFuture<Void> stopAsync() {
        server.halt(false);
        return server.stopped;
    }

    @Override
    public void close() {
        server.halt(true);
    }

    private static final class HostedServer extends DedicatedServer {
        private final ServerConnectionListener atlasConnections;
        private final LevelStorageSource.LevelStorageAccess storage;
        private final Shard shard;
        private final MinecraftChunkSubscriptions subscriptions;
        private final CompletableFuture<Void> ready;
        private final CompletableFuture<Void> stopped = new CompletableFuture<>();
        private MinecraftShardBridge bridge;
        private final MinecraftEntityLifecycle.Observer entityObserver;
        private final java.util.function.BiFunction<MinecraftServer, Shard, MinecraftShardBridge> bridgeFactory;

        HostedServer(Thread thread, LevelStorageSource.LevelStorageAccess storage,
                     net.minecraft.server.packs.repository.PackRepository packs, WorldStem world,
                     DedicatedServerSettings settings, Services services, Shard shard,
                     MinecraftChunkSubscriptions subscriptions, CompletableFuture<Void> ready,
                     java.util.function.BiFunction<MinecraftServer, Shard, MinecraftShardBridge> bridgeFactory,
                     MinecraftEntityLifecycle.Observer entityObserver) {
            super(thread, storage, packs, world, settings, DataFixers.getDataFixer(), services,
                    LoggerChunkProgressListener::createFromGameruleRadius);
            this.storage = storage;
            this.shard = shard;
            this.subscriptions = subscriptions;
            this.ready = ready;
            this.bridgeFactory = bridgeFactory;
            this.entityObserver = entityObserver;
            atlasConnections = new ServerConnectionListener(this) {
                @Override
                public void startTcpServerListener(InetAddress address, int port) {
                    // atlasnet-endpoint owns the one public Minecraft socket.
                }

                @Override
                public void tick() {
                    super.tick();
                    if (bridge != null) bridge.tickConnections();
                    if (entityObserver != null) entityObserver.tick();
                }
            };
        }

        @Override
        public ServerConnectionListener getConnection() {
            return atlasConnections;
        }

        @Override
        protected void createLevels(net.minecraft.server.level.progress.ChunkProgressListener progress) {
            super.createLevels(progress);
            for (var level : getAllLevels()) {
                if (subscriptions != null) subscriptions.observe(level);
                if (entityObserver != null) MinecraftEntityLifecycle.install(level, entityObserver);
            }
        }

        @Override
        public ServerLevel getLevel(ResourceKey<Level> dimension) {
            // Vanilla portals and dimension commands resolve their destinations here.
            return Level.OVERWORLD.equals(dimension) ? super.getLevel(dimension) : null;
        }

        @Override
        public void stopServer() {
            try {
                if (bridge != null) bridge.captureBeforeShutdown();
                if (entityObserver != null) entityObserver.beforeShutdown();
                super.stopServer();
            } finally {
                stopped.complete(null);
            }
        }

        @Override
        public boolean initServer() throws IOException {
            try {
                if (!super.initServer()) {
                    ready.completeExceptionally(new IOException("Vanilla Minecraft initialization failed"));
                    return false;
                }
                CheckpointPlayerData.install(getPlayerList(), storage, DataFixers.getDataFixer());
                bridge = bridgeFactory.apply(this, shard);
                ready.complete(null);
                return true;
            } catch (IOException | RuntimeException failure) {
                ready.completeExceptionally(failure);
                throw failure;
            }
        }
    }
}
