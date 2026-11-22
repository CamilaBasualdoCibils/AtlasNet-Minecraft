// The runtime host supplies Minecraft in the same Mojang namespace as the admission code.
val minecraftJar = providers.gradleProperty("minecraftAdmissionJar").orElse("")
val minecraftLibraries = providers.gradleProperty("minecraftAdmissionLibraries").orElse("")

dependencies {
    add("compileOnly", files(minecraftJar.map { path ->
        rootProject.file(path).also {
            check(path.isNotBlank() && it.isFile) {
                "Set minecraftAdmissionJar to the Mojang-mapped Minecraft 1.21.1 common/server JAR."
            }
        }
    }, minecraftLibraries.map { path ->
        rootProject.file(path).also {
            check(path.isNotBlank() && it.isDirectory) {
                "Set minecraftAdmissionLibraries to the extracted Minecraft 1.21.1 server libraries directory."
            }
        }.let { fileTree(it) { include("**/*.jar") } }
    }))
}
