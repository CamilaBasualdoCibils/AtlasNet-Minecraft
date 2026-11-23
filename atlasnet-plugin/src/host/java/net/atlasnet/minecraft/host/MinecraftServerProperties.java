package net.atlasnet.minecraft.host;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.Properties;
import net.minecraft.server.dedicated.DedicatedServerProperties;

final class MinecraftServerProperties {
    private MinecraftServerProperties() {}

    static Properties read(String json) {
        var root = JsonParser.parseString(json);
        var settings = new Properties();
        if (!root.isJsonObject() || !root.getAsJsonObject().has("minecraft")) return settings;
        var minecraft = root.getAsJsonObject().getAsJsonObject("minecraft");
        if (!minecraft.has("server-properties")) return settings;
        for (var entry : minecraft.getAsJsonObject("server-properties").entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive()) {
                throw new IllegalArgumentException("Minecraft server property must be a scalar: " + entry.getKey());
            }
            settings.setProperty(entry.getKey(), value.getAsString());
        }
        return settings;
    }

    static java.time.Duration checkpointInterval(String json) {
        var root = JsonParser.parseString(json);
        if (root.isJsonObject() && root.getAsJsonObject().has("minecraft")) {
            var minecraft = root.getAsJsonObject().getAsJsonObject("minecraft");
            if (minecraft.has("entity-store")) {
                var store = minecraft.getAsJsonObject("entity-store");
                if (store.has("persistence-interval-ms")) {
                    long value = store.get("persistence-interval-ms").getAsLong();
                    if (value <= 0) throw new IllegalArgumentException("Entity checkpoint interval must be positive");
                    return java.time.Duration.ofMillis(value);
                }
            }
        }
        return java.time.Duration.ofSeconds(5);
    }

    static DedicatedServerProperties vanilla(Properties settings) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        return new DedicatedServerProperties((Properties) settings.clone());
    }
}
