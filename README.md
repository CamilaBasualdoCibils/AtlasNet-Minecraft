# AtlasNet Minecraft Demo

An experimental demonstration and future integration testbed for AtlasNet's
distributed simulation framework. This bootstrap only initializes and logs. It is
not a production-ready distributed Minecraft server implementation.

## Requirements and loaders

- Minecraft Java Edition **1.21.1**, **JDK 21** (set `JAVA_HOME` to your JDK).
- Fabric Loader **0.20.1**, Fabric API **0.116.18+1.21.1**.
- Fabric Loom **1.14.10**, Gradle **9.2.1** via the checked-in wrapper.
- Fabric is active for clients and dedicated servers. NeoForge is planned and
  excluded from Gradle; building does not download NeoForge dependencies.

Versions are pinned in `gradle.properties` and the wrapper configuration. Loom is
pinned to a Java 21 compatible release; newer Loom lines require newer JDKs.
The first build requires Internet access to download Gradle, Minecraft, and Maven
dependencies. Minecraft's official Mojang mappings are used in both modules.

## Build and development

```sh
./gradlew build
./gradlew test
./gradlew :fabric:runClient
./gradlew :fabric:runServer
```

On Windows, use `gradlew.bat` instead of `./gradlew`. Root `build`, `check`,
`clean`, and `test` aggregate the active modules. No unit tests are present yet;
`test` is ready for tests as shared behavior is introduced.

The installable, remapped JAR is
`fabric/build/libs/atlasnet-fabric-0.1.0-dev.jar`. It includes the shared classes;
the common module's development JAR is not installed separately. The `-sources.jar` files and JARs under `build/devlibs/` are development
artifacts, not the installable mod.

Client and server use separate working directories, `fabric/run/client` and
`fabric/run/server`. The client needs a graphical desktop and OpenGL support.
The dedicated server runs with `nogui`. On the first server run, Minecraft writes
`fabric/run/server/eula.txt` and exits. Read the linked Minecraft EULA and, if you
agree, change `eula=false` to `eula=true`, then run the server task again. Enter
`stop` in the server console to shut down cleanly. Run directories, worlds, and
logs are ignored by Git.

Both environments log:

```text
[AtlasNet] Minecraft integration initialized
[AtlasNet] Fabric platform initialized
```

The client additionally logs `[AtlasNet] Client initialized`; the dedicated
server logs `[AtlasNet] Dedicated server initialized`. An integrated single-player
server uses the common initialization; the dedicated entrypoint is exclusive to
the physical dedicated-server environment.

## Install on a normal client or server

1. Install Minecraft **1.21.1** and the **Fabric Loader 0.20.1** profile or
   dedicated-server launcher using the [official installer](https://fabricmc.net/use/).
2. Place `atlasnet-fabric-0.1.0-dev.jar` and
   [Fabric API 0.116.18+1.21.1](https://modrinth.com/mod/fabric-api)
   in that instance's `mods/` directory.
3. Launch the Fabric client profile, or launch the Fabric dedicated-server JAR
   with Java 21 using the installer's instructions. Accept the server EULA if you
   agree, and restart as needed. Check `logs/latest.log` for the initialization
   messages above.

## Repository layout and future integration

- `common`: loader-independent code compiled against Minecraft. The
  `net.atlasnet.minecraft.integration` package reserves a location for the future
  Java SDK integration without defining bindings or abstractions prematurely.
- `fabric`: Fabric entrypoints and platform configuration. Client classes live in
  `src/client/java`; Loom separates their compile classpath from common/server
  code. Only the environment-filtered client entrypoint refers to client code.
  Shared classes are included in both the development classpath group and the
  final remapped Fabric JAR.
- `neoforge`: documentation only. A future platform module can reuse `common`;
  add a small adapter interface when actual loader-dependent behavior requires it.
- `AtlasNet`: pre-existing core submodule; this bootstrap does not modify it or
  include it in the Gradle build.

No SDK dependency, networking, gameplay, migration, entity synchronization,
commands, terrain storage, or background loops are implemented. Their design is
intentionally deferred until the AtlasNet Java SDK and integration requirements
are defined.

See `VALIDATION.md` for checks performed in the bootstrap environment.
