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
        create("atlasnet_minecraft") {
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
    // Use class directories in development; the shared module is not a separate mod.
    implementation(commonMain.get().output)
}

tasks.processResources {
    val metadata = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to project.property("minecraft_version").toString(),
        "loader_version" to project.property("loader_version").toString(),
    )
    inputs.properties(metadata)
    filesMatching("fabric.mod.json") { expand(metadata) }
}

tasks.jar { from(commonMain.map { it.output }) }
tasks.named<Jar>("sourcesJar") { from(commonMain.map { it.allSource }) }

val serverEulaFile = layout.projectDirectory.file("run/server/eula.txt").asFile

tasks.register("acceptDevServerEula") {
    group = "fabric"
    description = "Records your Minecraft EULA acceptance for the local Fabric development server."
    doLast {
        serverEulaFile.parentFile.mkdirs()
        serverEulaFile.writeText("# Accepted explicitly for the local Fabric development server.\neula=true\n")
    }
}

tasks.named<JavaExec>("runServer") {
    standardInput = System.`in`
}

val serverRun = tasks.named<JavaExec>("runServer")
tasks.register("prepareShardServer") {
    dependsOn(tasks.named("configureLaunch"), tasks.named("classes"))
    val launchFile = layout.buildDirectory.file("atlasnet/shard-launch.txt")
    outputs.file(launchFile)
    outputs.upToDateWhen { false }

    doLast {
        val run = serverRun.get()
        val file = launchFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText((listOf(layout.projectDirectory.dir("run/server").asFile.absolutePath, run.mainClass.get(),
            "-Djava.class.path=${run.classpath.asPath}", "-Datlasnet.minecraft.shard=true", "-Xrs")
            + run.allJvmArgs.filterNot { it.startsWith("@") } + listOf("--", "nogui")).joinToString("\n", postfix = "\n"))
    }
}

tasks.named<JavaExec>("runClient") {
    providers.environmentVariable("ATLASNET_MINECRAFT_CLIENT_LIBRARY").orNull?.let {
        systemProperty("atlasnet.minecraft.clientLibrary", it)
    }
}

val chunkStorageTestSources = sourceSets.create("chunkStorageTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("chunkStorageTest") {
    group = "verification"
    description = "Tests chunk storage against a fresh dedicated Minecraft Valkey instance."
    dependsOn(chunkStorageTestSources.classesTaskName)
    classpath = chunkStorageTestSources.runtimeClasspath
    mainClass.set("net.atlasnet.minecraft.fabric.server.storage.ValkeyChunkStorageTest")
}
