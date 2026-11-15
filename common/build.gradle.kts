plugins {
    id("fabric-loom")
}

base { archivesName.set("atlasnet-common") }

loom {
    // Shared code is compiled against Minecraft, without loader APIs.
    runs { clear() }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
}

// The common output is folded into each platform's remapped artifact.
// It is not an independently installable mod.
tasks.named("remapJar") { enabled = false }
tasks.named("remapSourcesJar") { enabled = false }
