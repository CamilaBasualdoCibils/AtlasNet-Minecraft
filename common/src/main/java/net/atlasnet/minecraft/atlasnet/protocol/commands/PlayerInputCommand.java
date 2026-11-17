package net.atlasnet.minecraft.atlasnet.protocol.commands;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

/** Movement and posture input carried by the existing Minecraft play packet codec. */
public final class PlayerInputCommand {
    public static final int ID = 0x4d430004;

    private PlayerInputCommand() {
    }

    public static boolean supports(Packet<?> packet) {
        if (packet instanceof ServerboundMovePlayerPacket) {
            return true;
        }
        if (packet instanceof ServerboundPlayerCommandPacket command) {
            return switch (command.getAction()) {
                case PRESS_SHIFT_KEY, RELEASE_SHIFT_KEY, START_SPRINTING, STOP_SPRINTING -> true;
                default -> false;
            };
        }
        return false;
    }
}
