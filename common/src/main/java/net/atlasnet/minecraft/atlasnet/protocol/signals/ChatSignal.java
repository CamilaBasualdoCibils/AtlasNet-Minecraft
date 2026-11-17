package net.atlasnet.minecraft.atlasnet.protocol.signals;

public record ChatSignal(long entityId, String displayName, String text) {
}
