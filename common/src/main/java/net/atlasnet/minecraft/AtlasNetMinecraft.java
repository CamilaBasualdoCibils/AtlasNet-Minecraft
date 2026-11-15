package net.atlasnet.minecraft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared entry point, independent of mod loaders and physical environment. */
public final class AtlasNetMinecraft {
    public static final String MOD_ID = "atlasnet";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private AtlasNetMinecraft() {
    }

    public static void initialize() {
        LOGGER.info("[AtlasNet] Minecraft integration initialized");
    }
}
