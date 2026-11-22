# AtlasNet ingress modules

These projects reserve packages for Minecraft integrations with the existing
AtlasNet Module Java SDK. Each compiles independently against the same external
SDK files. The scaffolds publish no providers and have no runtime entrypoint.

| Project | Execution context | Existing AtlasNet binding |
| --- | --- | --- |
| `atlasnet-endpoint` | Node / Client Ingress | `Registry.registerIngressTransport` with `IngressFactory`, `IngressTransport`, and `Connection` |
| `atlasnet-admissionHandler` | AtlasNet ingress admission | `Registry.registerAdmissionHandler` with `AdmissionHandler`, the binding for `IClientAdmissionHandler` |
| `atlasnet-spawnResolver` | AtlasNet ingress spawn resolution | `Registry.registerSpawnResolver` with `SpawnResolver`, the binding for `IClientSpawnResolver` |

## Intended client lifecycle

```text
Minecraft client
  → atlasnet-endpoint
  → atlasnet-admissionHandler
  → atlasnet-spawnResolver
  → AtlasNet shard selection
  → atlasnet-plugin (future shard runtime)
  → ShardAPI::OnClientSpawn
```

These are lifecycle stages mediated by AtlasNet. There are no Java project
dependencies between these components. The endpoint will handle Minecraft
protocol phases and optional native transport upgrades. Admission will use
authenticated Minecraft identity and honor the reserved
[`client.atlasnet-clients-only` setting](architecture.md#planned-transport-negotiation-and-admission).
When enabled, a compatible native session must be established before the
player is admitted. Spawn resolution will return the initial world name and
position, potentially from persisted player data. AtlasNet selects the owning
shard, and the future plugin shard runtime creates the player on the worker
through `OnClientSpawn`.

In the current native ingress path, admission runs during the AtlasNet Client
V1 handshake. Accepted admission bytes are passed to the spawn resolver unless
the listener has a static spawn configured. The upstream Java SDK provides
`Callbacks.admissionHandler` and `Callbacks.spawnResolver` to handle ownership
of boxed callback results. Callbacks run on the native calling thread, and a
future module must keep the JVM and its providers alive for the host's module
lifetime.

## Binding boundaries

The checked-in Java integration exposes all three provider registration
contracts. Its ingress transport adapter accepts byte streams that the Node
feeds into its Client V1 decoder. It exposes no Minecraft protocol endpoint
or binding entrypoint for admitting a raw vanilla session to that pipeline.
Vanilla protocol intake and how authenticated vanilla sessions enter AtlasNet's
lifecycle need separate design work.

AtlasNet currently loads native modules through `AtlasNetGetModule`. Its Java
SDK exposes the registry but does not supply JVM startup, Java JAR discovery,
or native plugin entrypoint glue. Future modules need a native host integration
using that existing entrypoint.

## Compile against the AtlasNet SDK

Install JDK 25 for these three projects and JDK 21 for the rest of the Minecraft
SDK. Build or obtain the generated `atlasnet-1.0.jar` from the matching AtlasNet
checkout and provision the GLib and jspecify JARs used to build it. Follow the
[AtlasNet Java build instructions](../AtlasNet/Integrations/Java/README.md).

Set these Gradle properties in your Gradle user home's `gradle.properties`
or use the corresponding `ORG_GRADLE_PROJECT_…` environment variables. This
configuration works for both CMake and the Gradle wrapper:

```properties
atlasnetJavaSdkJar=/absolute/path/to/atlasnet-1.0.jar
atlasnetJavaGiGlibJar=/absolute/path/to/glib-1.0.0-RC3.jar
atlasnetJavaJSpecifyJar=/absolute/path/to/jspecify-1.0.1.jar
```

The first property defaults to `AtlasNet/build/Integrations/Java/atlasnet-1.0.jar`.
Set it explicitly when using another AtlasNet build directory. If Gradle
cannot discover the installed JDKs, set `org.gradle.java.installations.paths`
to their comma-separated installation paths. The default Minecraft build
includes the new projects. Existing named plugin and mod builds remain usable
with JDK 21 alone.

The dependencies are compile-only. The build does not generate bindings,
initialize native code, or bundle dependency classes. The new JARs intentionally
contain only metadata for now. Running a future provider will require the
matching AtlasNet and Java-GI JARs, JDK 25, the same-build
`libatlasnet-gobject.so` and its native dependencies, library search paths, and
JVM native access configured as described in the upstream SDK instructions.
