plugins {
    `java-library`
}

// Adds a direct compile-only dependency on the existing AtlasNet Java SDK.
apply(from = rootProject.file("gradle/atlasnet-module.gradle.kts"))

val transportTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

val checkTransport by tasks.registering(JavaExec::class) {
    classpath = transportTest.runtimeClasspath
    mainClass.set("net.atlasnet.minecraft.endpoint.MinecraftTcpCodecTest")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
}

tasks.check {
    dependsOn(checkTransport)
}

dependencies {
    implementation(project(":common:core"))
}
