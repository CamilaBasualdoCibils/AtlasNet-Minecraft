package net.atlasnet.minecraft.sdk.plugin;

/** Server-to-player messages supported by an unmodified Minecraft client. */
public interface PlayerMessaging {
    /** Sends a plain-text system message to the player identified by its logical entity ID. */
    void sendSystemMessage(EntitySnapshot.Id player, String message);
}
