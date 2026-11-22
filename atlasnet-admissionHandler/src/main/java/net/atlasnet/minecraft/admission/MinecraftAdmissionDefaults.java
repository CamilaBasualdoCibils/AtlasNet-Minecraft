package net.atlasnet.minecraft.admission;

/** Edit and recompile these defaults to change the logical server's admission configuration. */
public final class MinecraftAdmissionDefaults {
    public static final MinecraftAdmissionConfig CONFIG = new MinecraftAdmissionConfig(
            "vanilla",
            256,
            2);

    private MinecraftAdmissionDefaults() {
    }
}
