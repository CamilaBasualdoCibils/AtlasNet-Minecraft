plugins {
    base
    id("fabric-loom") apply false
}

allprojects {
    group = providers.gradleProperty("maven_group").get()
    version = providers.gradleProperty("mod_version").get()
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
            withSourcesJar()
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(21)
            options.encoding = "UTF-8"
        }
    }
}

tasks.named("build") { dependsOn(":common:build", ":fabric:build") }
tasks.named("check") { dependsOn(":common:check", ":fabric:check") }
tasks.named("clean") { dependsOn(":common:clean", ":fabric:clean") }
tasks.register("test") {
    group = "verification"
    description = "Runs unit tests in both active modules."
    dependsOn(":common:test", ":fabric:test")
}
