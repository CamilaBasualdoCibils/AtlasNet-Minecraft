package net.atlasnet.minecraft.admission;

import java.util.Objects;

/** Logical server settings presented during Minecraft 1.21.1 admission. */
public record MinecraftAdmissionConfig(String brand, int compressionThreshold, int functionPermissionLevel) {
    public MinecraftAdmissionConfig {
        Objects.requireNonNull(brand, "brand");
        if (brand.isBlank() || brand.length() > 64) {
            throw new IllegalArgumentException("Brand must contain 1 to 64 characters");
        }
        if (compressionThreshold < -1) {
            throw new IllegalArgumentException("Compression threshold must be -1 (disabled) or nonnegative");
        }
        if (functionPermissionLevel < 0 || functionPermissionLevel > 4) {
            throw new IllegalArgumentException("Function permission level must be between 0 and 4");
        }
    }
}
