package net.atlasnet.minecraft.atlasnet.protocol.commands;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

/** Vanilla inventory, item use, and local entity interactions on the owning shard. */
public final class GameplayInputCommand {
    public static final int ID = 0x4d430006;
    public static final int MAX_PACKET_BYTES = 16 * 1024;

    private GameplayInputCommand() {}

    public static boolean supports(Packet<?> packet) {
        if (packet instanceof ServerboundPlayerActionPacket action) {
            return switch (action.getAction()) {
                case RELEASE_USE_ITEM, DROP_ITEM, DROP_ALL_ITEMS, SWAP_ITEM_WITH_OFFHAND -> true;
                default -> false;
            };
        }
        return packet instanceof ServerboundContainerClickPacket
                || packet instanceof ServerboundContainerClosePacket
                || packet instanceof ServerboundInteractPacket
                || packet instanceof ServerboundSetCarriedItemPacket
                || packet instanceof ServerboundSetCreativeModeSlotPacket
                || packet instanceof ServerboundSwingPacket
                || packet instanceof ServerboundUseItemPacket;
    }
}
