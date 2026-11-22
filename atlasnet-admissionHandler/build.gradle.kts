plugins {
    `java-library`
}

// Adds a direct compile-only dependency on the existing AtlasNet Java SDK.
apply(from = rootProject.file("gradle/atlasnet-module.gradle.kts"))

apply(from = rootProject.file("gradle/minecraft-runtime.gradle.kts"))

dependencies {
    implementation(project(":common:core"))
}
