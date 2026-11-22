package net.atlasnet.minecraft;

public final class DemoStartup {
    private static final System.Logger LOGGER = System.getLogger("atlasnet_minecraft");

    private DemoStartup() {
    }

    public static void log(String environment) {
        LOGGER.log(System.Logger.Level.INFO, "AtlasNet Minecraft demo loaded ({0})", environment);
    }
}
