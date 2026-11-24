# AtlasNet Minecraft

AtlasNet Minecraft is the foundation for a public Minecraft integration with
AtlasNet. The active projects provide Java API contracts and compile-only
examples. Minecraft gameplay, networking, distributed simulation, and loader
adapters are future work.

The server plugin is designed to serve vanilla Minecraft clients and clients
with the optional AtlasNet mod in the same authoritative simulation. The client
mod is designed to retain ordinary Minecraft connectivity and negotiate a native
AtlasNet session on servers that support it. See the [architecture](docs/architecture.md)
for the planned connections, admission policy, and platform boundaries.

## Projects

| Gradle project | Responsibility |
| --- | --- |
| `:common:core` | Platform-independent shared values, currently a namespaced resource key |
| `:common:mod` | Content definitions and registry contracts shared by client and server |
| `:atlasnet-plugin` | Base server lifecycle, world and entity access, events, scheduling, and authority contracts |
| `:atlasnet-endpoint` | Node ingress endpoint scaffold |
| `:atlasnet-admissionHandler` | Client admission provider scaffold |
| `:atlasnet-spawnResolver` | Initial spawn resolver scaffold |
| `:atlasnet-mod:client` | Client mod content registration contract, independent of server APIs |
| `:atlasnet-mod:server` | Server mod lifecycle and content synchronization contracts layered on the plugin API |

Examples live at `examples/plugin/`, `examples/mod/client/`, and
`examples/mod/server/`. Each declares a single dependency on its corresponding
SDK. Future adapter notes live under `platforms/`. The archived prototype and
its original build files are under [`legacy/`](legacy/README.md). The
AtlasNet submodule remains at `AtlasNet/`. The Minecraft build excludes the
archive and builds AtlasNet and its Java bindings from the submodule.

## Build

Use CMake 3.25 or later, Ninja, Clang, vcpkg, JDK 21, and JDK 25. Set
`VCPKG_ROOT` and point `JAVA_HOME` to JDK 25. Provision Java-GI and the Minecraft
1.21.1 runtime as described in the [module setup](docs/atlasnet-modules.md#compile-against-the-atlasnet-sdk).
The wrapper may download Gradle on first use.

```sh
git submodule update --init --recursive
cmake --preset minecraft \
  -DATLASNET_JAVA_GI_EXECUTABLE=/path/to/java-gi/bin/java-gi \
  '-DATLASNET_JAVA_RUNTIME_JARS=/path/to/glib-1.0.0-RC3.jar;/path/to/jspecify-1.0.1.jar'
cmake --build build
cmake --build build --target atlasnet-minecraft-stack
cmake --build build --target atlasnet-plugin
cmake --build build --target atlasnet-endpoint
cmake --build build --target atlasnet-admissionHandler
cmake --build build --target atlasnet-spawnResolver
cmake --build build --target atlasnet-mod-client
cmake --build build --target atlasnet-mod-server
```

The default build includes `atlasnet-sdk` for the Gradle projects and
`atlasnet-minecraft-stack` for the AtlasNet executables, Valkey, and the four
Minecraft native modules. The root build passes the generated SDK JAR and
its compile-time dependencies to Gradle. The `server` build preset selects
the stack target in VS Code. CMake rediscovers tools provisioned in
`.tools/minecraft-build-deps` after a cache reset. If you configured `build/` with the
earlier standalone Minecraft build, use `cmake --fresh --preset minecraft`
with the same Java-GI options once to switch toolchains.

The wrapper also works directly after the binding JAR is built and the
Gradle dependency properties are set:

```sh
./gradlew :atlasnet-plugin:build
./gradlew :atlasnet-endpoint:build
./gradlew :atlasnet-admissionHandler:build
./gradlew :atlasnet-spawnResolver:build
./gradlew :atlasnet-mod:client:build
./gradlew :atlasnet-mod:server:build
./gradlew build
```

| CMake target | Artifact relative to the repository root |
| --- | --- |
| `atlasnet-plugin` | `atlasnet-plugin/build/libs/atlasnet-plugin.jar` |
| `atlasnet-endpoint` | `atlasnet-endpoint/build/libs/atlasnet-endpoint.jar` |
| `atlasnet-admissionHandler` | `atlasnet-admissionHandler/build/libs/atlasnet-admissionHandler.jar` |
| `atlasnet-spawnResolver` | `atlasnet-spawnResolver/build/libs/atlasnet-spawnResolver.jar` |
| `atlasnet-mod-client` | `atlasnet-mod/client/build/libs/atlasnet-mod-client.jar` |
| `atlasnet-mod-server` | `atlasnet-mod/server/build/libs/atlasnet-mod-server.jar` |
| `example-plugin` | `examples/plugin/build/libs/atlasnet-plugin-example.jar` |
| `example-mod-client` | `examples/mod/client/build/libs/atlasnet-mod-client-example.jar` |
| `example-mod-server` | `examples/mod/server/build/libs/atlasnet-mod-server-example.jar` |

The common JARs are
`common/core/build/libs/atlasnet-minecraft-core.jar` and
`common/mod/build/libs/atlasnet-minecraft-mod-common.jar`. Build them directly
with `./gradlew :common:core:build :common:mod:build`. Gradle exports the SDK
dependencies used by public contracts. For a manual classpath, supply each
upstream JAR shown in the [dependency graph](docs/architecture.md#module-dependencies).

The JARs are libraries for compiling extensions. Installable Paper, Fabric, and
NeoForge artifacts will come from future platform-specific packaging.
