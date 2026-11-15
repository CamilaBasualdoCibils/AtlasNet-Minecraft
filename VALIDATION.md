# Bootstrap validation

Validated on November 14, 2026 (America/Los_Angeles), Linux x86_64,
Temurin JDK 21.0.13, Gradle 9.2.1.

| Check | Result |
| --- | --- |
| `./gradlew --version` | Passed; official wrapper downloaded and ran Gradle 9.2.1 on Java 21. |
| Gradle distribution integrity | Downloaded distribution matched the official SHA-256; checksum pinned in wrapper properties. |
| `./gradlew build test projects --no-daemon` | Passed. Both modules compiled, including Fabric's separate client source set. |
| Final `./gradlew build test :fabric:runServer --no-daemon` | Passed after development classpath cleanup. |
| Unit test tasks | Configured and executed as `NO-SOURCE`; no unit tests exist in this logging-only bootstrap. |
| Fabric artifact | `fabric/build/libs/atlasnet-fabric-0.1.0-dev.jar` built and remapped successfully. |
| Packaged metadata | JSON parsed successfully; required identity, environment, expanded versions, three entrypoints, and entrypoint class paths checked against JAR contents. |
| Shared code packaging | Shared `AtlasNetMinecraft.class` present in the installable Fabric JAR. |
| Client launch | Launched twice with `:fabric:runClient`; confirmed common, Fabric, and client initialization on the render thread. Renderer texture atlases and sound engine initialized. |
| Dedicated-server launch | Confirmed common, Fabric, and dedicated-server initialization. No client initialization or client-only classloading failure. |
| NeoForge exclusion | `projects` lists only `:common` and `:fabric`. No NeoForge build configuration or dependency exists. |

## Limits and environment observations

- Dedicated-server mod initialization passed. World startup, joining, and gameplay
  were not tested: Minecraft generated `fabric/run/server/eula.txt` and exited
  because the EULA remains unaccepted. Acceptance is left to the operator.
- Clients were intentionally terminated with SIGTERM after the initialization
  smoke checks; Gradle consequently reported exit status 143. This is not a clean
  client shutdown test or an automated gameplay test.
- This machine lacks Minecraft's optional `flite` narrator native library. The
  client logged narrator initialization errors and continued initializing its
  renderer and audio. Loom also warned about absent empty resources directories
  for source sets with no resources. Neither prevented mod initialization.
- Production installation was documented but was not separately launched outside
  Loom. The final remapped JAR was inspected directly.
- No AtlasNet SDK, networking, or simulation behavior exists to validate yet.

The final local logs are under the ignored `.tools/final-build-server.log` and
`.tools/final-client.log`, and Minecraft logs are under `fabric/run/`. A local JDK
and Gradle cache were placed in ignored `.tools/` for validation because this host
had no system Java installation. They are not repository dependencies; normally
install JDK 21 and run the wrapper as documented in README.md.
