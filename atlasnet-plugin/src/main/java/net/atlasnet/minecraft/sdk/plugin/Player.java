package net.atlasnet.minecraft.sdk.plugin;

/** A player observed by the server adapter. Display names are presentation data. */
public interface Player {
    EntitySnapshot entity();

    String displayName();
}
