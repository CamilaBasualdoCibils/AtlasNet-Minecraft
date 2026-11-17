package net.atlasnet.minecraft.atlasnet.protocol.signals;

public record PlayerJoinedSignal(long entityId, String displayName) {
}
