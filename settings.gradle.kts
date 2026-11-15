pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
    }
    plugins {
        id("fabric-loom") version providers.gradleProperty("loom_version").get()
    }
}

rootProject.name = "atlasnet-minecraft"
include("common", "fabric")
// NeoForge is deliberately not a Gradle project yet.
