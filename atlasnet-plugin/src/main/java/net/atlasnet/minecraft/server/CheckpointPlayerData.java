package net.atlasnet.minecraft.server;

import com.mojang.datafixers.DataFixer;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;

/** Adapts the vanilla 1.21.1 player-data load used by placeNewPlayer. */
final class CheckpointPlayerData extends PlayerDataStorage {
    private record Restore(UUID player, CompoundTag data) {}
    private static final ThreadLocal<Restore> RESTORE = new ThreadLocal<>();
    private final PlayerDataStorage original;

    private CheckpointPlayerData(LevelStorageSource.LevelStorageAccess storage, DataFixer fixer,
                                 PlayerDataStorage original) {
        super(storage, fixer);
        this.original = original;
    }

    static void install(PlayerList players, LevelStorageSource.LevelStorageAccess storage, DataFixer fixer) {
        try {
            var field = PlayerList.class.getDeclaredField("playerIo");
            field.setAccessible(true);
            var original = (PlayerDataStorage) field.get(players);
            field.set(players, new CheckpointPlayerData(storage, fixer, original));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot attach Minecraft 1.21.1 checkpoint storage", failure);
        }
    }

    static void withCheckpoint(ServerPlayer player, CompoundTag data, Runnable join) {
        withCheckpoint(player.getUUID(), data, join);
    }

    static void withCheckpoint(UUID player, CompoundTag data, Runnable join) {
        if (RESTORE.get() != null) throw new IllegalStateException("Nested player restore");
        RESTORE.set(new Restore(player, data));
        try {
            join.run();
        } finally {
            RESTORE.remove();
        }
    }

    @Override
    public Optional<CompoundTag> load(Player player) {
        var restore = RESTORE.get();
        if (restore == null || !restore.player().equals(player.getUUID())) return original.load(player);
        player.load(restore.data());
        return Optional.of(restore.data());
    }

    @Override
    public void save(Player player) {
        original.save(player);
    }
}
