plugins {
    id("fabric-loom")
}

evaluationDependsOn(":common")
val commonSourceSets = project(":common").extensions.getByType<SourceSetContainer>()
val commonMain = commonSourceSets.named("main")

base { archivesName.set("atlasnet-fabric") }

loom {
    splitEnvironmentSourceSets()
    mods {
        create("atlasnet") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets["client"])
            sourceSet(commonMain.get())
        }
    }
    runs {
        named("client") { runDir("run/client") }
        named("server") {
            runDir("run/server")
            programArg("nogui")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    // Use class directories in development; the shared module is not a separate mod.
    implementation(commonMain.get().output)
}

tasks.processResources {
    val metadata = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to project.property("minecraft_version").toString(),
        "loader_version" to project.property("loader_version").toString(),
        "fabric_api_version" to project.property("fabric_api_version").toString()
    )
    inputs.properties(metadata)
    filesMatching("fabric.mod.json") { expand(metadata) }
}

tasks.jar { from(commonMain.map { it.output }) }
tasks.named<Jar>("sourcesJar") { from(commonMain.map { it.allSource }) }
