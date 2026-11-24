import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

// The generated Java-GI bindings require Java 25. The other Minecraft SDKs target Java 21.
extensions.configure<JavaPluginExtension> {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

fun sdkJar(property: String, defaultPath: String? = null): Provider<File> {
    val configuredPath = providers.gradleProperty(property)
    val path = if (defaultPath == null) configuredPath.orElse("") else configuredPath.orElse(defaultPath)
    return path.map { value ->
        check(value.isNotBlank()) {
            "Set Gradle property '$property' to the matching AtlasNet Java SDK JAR. See docs/atlasnet-modules.md."
        }
        rootProject.file(value).also { jar ->
            check(jar.isFile) {
                "Gradle property '$property' points to a missing JAR: $jar. See docs/atlasnet-modules.md."
            }
        }
    }
}

dependencies.add("compileOnly", files(
    sdkJar("atlasnetJavaSdkJar", "build/AtlasNet/Integrations/Java/atlasnet-1.0.jar"),
    sdkJar("atlasnetJavaGiGlibJar"),
    sdkJar("atlasnetJavaJSpecifyJar"),
))
