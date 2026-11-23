plugins {
    `java-library`
}

dependencies {
    api(project(":common:core"))
}

apply(from = rootProject.file("gradle/minecraft-runtime.gradle.kts"))
apply(from = rootProject.file("gradle/atlasnet-module.gradle.kts"))

// Keep the public plugin API usable by the existing Java 21 consumers.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

repositories { mavenCentral() }

val host by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations.named(host.implementationConfigurationName) {
    extendsFrom(configurations.implementation.get())
}
configurations.named(host.compileOnlyConfigurationName) {
    extendsFrom(configurations.compileOnly.get())
}
configurations.named(host.runtimeClasspathConfigurationName) {
    extendsFrom(configurations.compileOnly.get())
}
dependencies {
    add(host.implementationConfigurationName, "org.postgresql:postgresql:42.7.14")
    add(host.implementationConfigurationName, project(":atlasnet-admissionHandler"))
    add(host.implementationConfigurationName, project(":atlasnet-endpoint"))
    add(host.implementationConfigurationName, project(":atlasnet-spawnResolver"))
}
tasks.named<JavaCompile>(host.compileJavaTaskName) {
    options.release.set(25)
}
val hostClasspathFile = layout.buildDirectory.file("minecraft-host/classpath.txt")
tasks.register("prepareMinecraftHost") {
    dependsOn(host.classesTaskName, host.runtimeClasspath)
    inputs.files(host.runtimeClasspath)
    outputs.file(hostClasspathFile)
    doLast {
        val file = hostClasspathFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(host.runtimeClasspath.asPath)
    }
}
