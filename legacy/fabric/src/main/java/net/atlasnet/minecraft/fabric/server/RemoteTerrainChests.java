package net.atlasnet.minecraft.fabric.server;

import net.atlasnet.minecraft.fabric.server.storage.ShardChunkIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Chest-style menus on the player shard, with slot changes committed on the terrain shard. */
final class RemoteTerrainChests {
    private RemoteTerrainChests() {}

    static CompoundTag snapshot(ServerLevel level, BlockPos pos, Player player) {
        var storage = findStorage(level, pos, player);
        if (storage == null) {
            return null;
        }
        var result = new CompoundTag();
        result.putString("Title", Component.Serializer.toJson(storage.title(), level.registryAccess()));
        result.put("Slots", saveSlots(storage.slots(), level.registryAccess()));
        return result;
    }

    static void open(ServerPlayer player, BlockPos pos, CompoundTag snapshot) {
        if (!player.isAlive() || !player.canInteractWithBlock(pos, 1.0)) {
            return;
        }
        var slots = snapshot.getList("Slots", Tag.TAG_COMPOUND);
        if (slots.size() != 27 && slots.size() != 54) {
            return;
        }
        var title = Component.Serializer.fromJson(snapshot.getString("Title"), player.registryAccess());
        if (title == null) {
            return;
        }
        var copy = new SimpleContainer(slots.size());
        loadSlots(copy, slots, player.registryAccess());
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, opener) -> new RemoteMenu(id, inventory, copy, pos), title));
    }

    static CompoundTag input(RemoteMenu menu, ServerPlayer player, ServerboundContainerClickPacket click) {
        var action = click.getClickType();
        if (click.getContainerId() != menu.containerId || click.getStateId() != menu.getStateId()
                || !menu.stillValid(player) || click.getSlotNum() < 0 || action == ClickType.THROW
                || action == ClickType.QUICK_CRAFT) {
            return null;
        }
        var state = new CompoundTag();
        state.putInt("Slot", click.getSlotNum());
        state.putInt("Button", click.getButtonNum());
        state.putString("Action", action.name());
        var registries = player.registryAccess();
        state.put("Chest", saveSlots(menu.getContainer(), registries));
        state.put("Inventory", player.getInventory().save(new ListTag()));
        state.put("Carried", menu.getCarried().saveOptional(registries));
        return state;
    }

    static ClickResult simulate(ServerLevel level, BlockPos pos, Player player, CompoundTag request) {
        var registries = level.registryAccess();
        player.getInventory().load(request.getList("Inventory", Tag.TAG_COMPOUND));
        var storage = findStorage(level, pos, player);
        if (storage == null) {
            return null;
        }
        var slots = storage.slots();
        var response = new CompoundTag();
        var current = saveSlots(slots, registries);
        var expected = request.getList("Chest", Tag.TAG_COMPOUND);
        if (!current.equals(expected)) {
            response.put("Slots", current);
            return new ClickResult(response, null, null);
        }

        // Simulate on detached inventories so an oversized reply cannot change real slots.
        var proposed = new SimpleContainer(slots.getContainerSize());
        loadSlots(proposed, current, registries);
        var menu = menu(0, player.getInventory(), proposed);
        menu.setCarried(ItemStack.parseOptional(registries, request.getCompound("Carried")));
        var click = ClickType.valueOf(request.getString("Action"));
        int slot = request.getInt("Slot");
        if (slot < 0 || slot >= menu.slots.size() || click == ClickType.THROW || click == ClickType.QUICK_CRAFT) {
            return null;
        }
        menu.clicked(slot, request.getInt("Button"), click, player);
        response.put("Slots", saveSlots(proposed, registries));
        response.put("Inventory", player.getInventory().save(new ListTag()));
        response.put("Carried", menu.getCarried().saveOptional(registries));
        return new ClickResult(response, slots, proposed);
    }

    static void finish(RemoteMenu menu, ServerPlayer player, CompoundTag request, CompoundTag result) {
        if (result != null) {
            var registries = player.registryAccess();
            var slots = result.getList("Slots", Tag.TAG_COMPOUND);
            if (slots.size() == menu.getContainer().getContainerSize()) {
                loadSlots(menu.getContainer(), slots, registries);
                if (result.contains("Inventory")) {
                    var before = new Inventory(player);
                    before.load(request.getList("Inventory", Tag.TAG_COMPOUND));
                    var after = new Inventory(player);
                    after.load(result.getList("Inventory", Tag.TAG_COMPOUND));
                    for (int i = 0; i < after.getContainerSize(); ++i) {
                        if (!ItemStack.matches(before.getItem(i), after.getItem(i))) {
                            player.getInventory().setItem(i, after.getItem(i));
                        }
                    }
                    menu.setCarried(ItemStack.parseOptional(registries, result.getCompound("Carried")));
                }
            }
        }
        menu.finishClick(player);
    }

    private static Storage findStorage(ServerLevel level, BlockPos pos, Player player) {
        var state = level.getBlockState(pos);
        if (!(level.getBlockEntity(pos) instanceof BaseContainerBlockEntity blockEntity)
                || !blockEntity.canOpen(player)) {
            return null;
        }
        if (state.getBlock() instanceof ChestBlock chest) {
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                var other = pos.relative(ChestBlock.getConnectedDirection(state));
                if (!ShardChunkIO.ownsTerrain(ChunkPos.asLong(other)) || !level.hasChunkAt(other)
                        || !(level.getBlockEntity(other) instanceof BaseContainerBlockEntity otherChest)
                        || !otherChest.canOpen(player)) {
                    return null;
                }
            }
            var slots = ChestBlock.getContainer(chest, state, level, pos, false);
            var provider = state.getMenuProvider(level, pos);
            return slots == null || provider == null ? null : new Storage(slots, provider.getDisplayName());
        }
        return blockEntity instanceof BarrelBlockEntity barrel ? new Storage(barrel, barrel.getDisplayName()) : null;
    }

    private static ChestMenu menu(int id, Inventory inventory, SimpleContainer container) {
        return container.getContainerSize() == 54 ? ChestMenu.sixRows(id, inventory, container)
                : ChestMenu.threeRows(id, inventory, container);
    }

    private static ListTag saveSlots(Container slots, HolderLookup.Provider registries) {
        var result = new ListTag();
        for (int i = 0; i < slots.getContainerSize(); ++i) {
            result.add(slots.getItem(i).saveOptional(registries));
        }
        return result;
    }

    private static void loadSlots(Container container, ListTag slots, HolderLookup.Provider registries) {
        for (int i = 0; i < slots.size(); ++i) {
            container.setItem(i, ItemStack.parseOptional(registries, slots.getCompound(i)));
        }
    }

    private record Storage(Container slots, Component title) {}

    record ClickResult(CompoundTag response, Container live, SimpleContainer proposed) {
        void commit() {
            if (live != null) {
                for (int i = 0; i < live.getContainerSize(); ++i) {
                    if (!ItemStack.matches(live.getItem(i), proposed.getItem(i))) {
                        live.setItem(i, proposed.getItem(i));
                    }
                }
            }
        }
    }

    static final class RemoteMenu extends ChestMenu {
        private final BlockPos target;
        private boolean inFlight;
        private boolean closed;

        RemoteMenu(int id, Inventory inventory, SimpleContainer slots, BlockPos target) {
            super(slots.getContainerSize() == 54 ? MenuType.GENERIC_9x6 : MenuType.GENERIC_9x3,
                    id, inventory, slots, slots.getContainerSize() / 9);
            this.target = target;
        }

        BlockPos target() {
            return target;
        }

        void beginClick() {
            inFlight = true;
        }

        void finishClick(Player player) {
            inFlight = false;
            if (closed) {
                super.removed(player);
            } else {
                broadcastFullState();
            }
        }

        @Override
        public void removed(Player player) {
            closed = true;
            if (!inFlight) {
                super.removed(player);
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return player.isAlive() && (inFlight || player.canInteractWithBlock(target, 1.0));
        }
    }
}
