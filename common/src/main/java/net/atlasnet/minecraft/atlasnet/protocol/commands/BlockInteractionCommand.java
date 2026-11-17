package net.atlasnet.minecraft.atlasnet.protocol.commands;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;

/** Sends vanilla block use and break intent for the sender's authoritative player. */
public final class BlockInteractionCommand {
    public static final int ID = 0x4d430005;

    private BlockInteractionCommand() {}

    public static boolean supports(Packet<?> packet) {
        if (packet instanceof ServerboundUseItemOnPacket) {
            return true;
        }
        if (packet instanceof ServerboundPlayerActionPacket action) {
            return switch (action.getAction()) {
                case START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> true;
                default -> false;
            };
        }
        return false;
    }
}
