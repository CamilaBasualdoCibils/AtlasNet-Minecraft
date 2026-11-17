# AtlasNet Minecraft Demo

This Fabric demo for Minecraft Java Edition 1.21.1 runs dedicated servers
inside AtlasNet shard workers. The client joins through SteamNetSock and
receives Minecraft world packets and demo chat through AtlasNet Signals.

## Build and run

Install CMake 3.20 or newer and a JDK 21. Put the JDK on PATH, set
`JAVA_HOME`, or pass `-DATLASNET_MINECRAFT_JAVA_HOME=/path/to/jdk21` at CMake
configuration. For direct Gradle commands, use `JAVA_HOME`. The first Gradle
build downloads dependencies.

```sh
cmake -S . -B build
cmake --build build --target minecraft-build
cmake --build build --target minecraft-client
cmake --build build --target minecraft-server
```

CMake delegates to `./gradlew :fabric:build`, `./gradlew :fabric:runClient`,
and `./gradlew :fabric:runServer`, respectively. Launch targets run in the
foreground. The client needs a graphical session. The server runs with
`nogui`. Enter `stop` at its console to shut it down cleanly.

Read the [Minecraft EULA](https://www.minecraft.net/eula) before starting the
development server. If you agree, run the following once before launching it.
This opt-in writes `fabric/run/server/eula.txt` and also works in unattended
development setup.

```sh
./gradlew :fabric:acceptDevServerEula
```

Client options and logs live under `fabric/run/client`. The dedicated server
keeps its world, configuration, and logs under `fabric/run/server`.

## Project layout

- `common` contains loader-independent code. Fabric includes its classes in
  the remapped mod JAR at `fabric/build/libs/atlasnet-fabric-0.1.0-dev.jar`.
- `fabric` provides loader entrypoints and uses Loom's isolated client source
  set. Its mod ID is `atlasnet_minecraft`.
- `neoforge` reserves space for a future loader adapter. Gradle configures only
  `common` and `fabric`.

The Gradle wrapper pins Gradle 9.2.1. The build pins Fabric Loom 1.14.10 and
Fabric Loader 0.20.1, and uses Minecraft 1.21.1's official Mojang mappings.
The bootstrap uses only the entrypoint interfaces supplied by Fabric Loader.

## Include in a parent CMake build

A parent project can register the same three targets with:

```cmake
add_subdirectory("/path/to/AtlasNet-Minecraft" minecraft-demo)
```

If the parent build uses Java 25, point `ATLASNET_MINECRAFT_JAVA_HOME` at a
separate JDK 21 installation. The native shard module and client JNI bridge
link against the AtlasNet SDK. Fabric uses these bridges directly; generated
GObject/Java bindings and the NeoForge adapter are outside this path.

## AtlasNet protocol groundwork

The shared `net.atlasnet.minecraft.atlasnet.protocol` package defines the
application IDs and payload codecs for `ChatCommand`, `ChatSignal`,
`PlayerJoinedSignal`, `PlayerLeftSignal`, and `PlayerRegionSignal`. The codecs
operate on payload bytes for the AtlasNet raw Command and Signal APIs. Chat
commands carry text only. Region payloads preserve AtlasNet's signed region ID,
generation bits, dimension, bounds, and clipping planes. Missing geometry can be
represented explicitly. `./gradlew :common:check` runs the codec checks.

The shard plugin embeds the Fabric dedicated server using the Java 21 JVM.
`BuildServer` exports the development launch configuration from Loom. In
shard mode the server suppresses its vanilla TCP listener. The worker uses
`OnClientSpawn` to call the Fabric player adapter, which creates players on
the Minecraft server thread with identities derived from AtlasNet client IDs.

`RunClient` loads the Minecraft-owned JNI bridge. Multiplayer and Direct
Connect use AtlasNet's Client API over SteamNetworkingSockets and display
connection status. The demo uses the AtlasNet SteamNetSock ingress module and
has no dependency on the SimpleGame example.
Configuration and play packets use Minecraft's codecs and travel as ordered
AtlasNet Signals. Chat uses an AtlasNet Command and broadcast Signal. Join and
leave use broadcast Signals, and each player's shard sends its region
descriptor directly. In the world, use `/atlasnet client showRegion true` or
`/atlasnet client showRegion false` to toggle the region ID and nearby
vertical wireframes along the XZ region edges, clipped near the camera.
The top-left shard UUID is visible by default. Use
`/atlasnet client metrics false` to hide it or `/atlasnet client metrics true`
to restore it.
The shard starts only `minecraft:overworld` and rejects other spawn
dimensions. AtlasNet's reserved Overworld ID is `0` in `AtlasNetWorlds`.

Movement and posture commands use Minecraft's play packet codec. The owning
shard resolves the player from the AtlasNet command context and invokes the
normal Minecraft movement listener on the server thread. It publishes accepted
position changes to AtlasNet and uses Minecraft's existing player tracker for
same-shard observers. Cross-shard visibility uses SpatialInterest and an
authoritative pose snapshot, then sends pose changes through Spatial Audience.
In shared terrain mode, one scoped SpatialRPC discovers nearby player poses
without sending an EntityRPC to each terrain entity in the interest.
Visibility transitions use the direct recipient API because the current
AtlasNet Signal router skips `Audience::Entities`.

Walking beyond AtlasNet's region transfer threshold transfers a live player to
the new shard. The source freezes the Minecraft player while the destination
stages the player's compressed Minecraft NBT. The client keeps its Overworld,
chunk cache, and SteamNetSock connection through the handoff. The region overlay
and shard UUID update after the destination receives input through AtlasNet's
committed route. Living, unmounted, awake Overworld players are eligible for this
handoff. Player statistics and advancements still use each worker's local files.
Run matching builds of the client and shard module.

## Run the AtlasNet local stack

Open this repository as a single folder in VS Code and run **AtlasNet: Open
Local Stack**, then **Run stack**. The [stack configuration](.vscode/atlasnet-stack.json)
starts two AtlasNet nodes with one shard each and a KMeans2D target of two
regions on AtlasNet's XZ spatial plane. Both nodes use the Database, Shard,
ControllerEligible, and ClientIngress capabilities, the same Minecraft settings, and a shared AtlasNet
Valkey process. Their SteamNetSock ingress ports are `5919` and `5920`. The
stack tool assigns distinct cluster and handshake ports. Connect one Fabric
client to each ingress endpoint through Multiplayer. Both endpoints use the
same Overworld spawn, so separate ingress connections can be assigned to the
same authoritative shard.

The stack uses `scripts/minecraft-stack-gdb` to debug the AtlasNet nodes and
detach the Minecraft shard workers at fork. This lets Java handle its own
signals and start its helper processes. Attach a debugger to a worker
separately when needed.

The configuration uses a sibling AtlasNet checkout at `../AtlasNet` with
SteamNetSock enabled. Configure and build AtlasNet's module SDK once so the
Minecraft project can locate its library and dependency headers.

```sh
cmake -S . -B build
cmake --build build --target BuildServer
```

The stack panel runs `BuildServer` before launch. That target builds
`build/native/AtlasNetMinecraftShard.so` and the Fabric artifact. The panel
launches the prebuilt AtlasNet node and Valkey using the saved configuration.
Provision the node, worker, SteamNetSock module, and Valkey before launching.
The Minecraft build reads the AtlasNet SDK without rebuilding it.

`BuildClient` builds the Fabric artifact and its SteamNetSock JNI library. `RunClient` builds it and launches
Loom's Fabric client. `RunServer` builds the server dependencies and launches
Loom's standalone Fabric dedicated server. Use the stack panel to run the
AtlasNet node and its shard workers.

For a different AtlasNet checkout or build directory, set
`ATLASNET_SDK_SOURCE_DIR` and `ATLASNET_SDK_BUILD_DIR` in CMake and update the
executable and module paths in the stack configuration to match.

The native plugin launches Fabric inside each shard worker JVM. Use the
local development EULA setup above before starting the stack. Minecraft's
working files live under `fabric/run/server/valkey-normal-shards/<shard-id>` and the
workers share the normal Overworld's chunk data through Minecraft Valkey.


The stack prelaunch also starts Minecraft's dedicated Valkey at
`redis://127.0.0.1:46382/0` with append-only persistence in
`run/minecraft-valkey-normal`. Connection browsers need a separate entry for this
instance. The stack panel manages AtlasNet's Valkey on port `46380`. After
stopping the stack, run `python3 scripts/minecraft-valkey.py stop` to save and
stop the Minecraft instance.
Use `MINECRAFT_VALKEY_PORT`, `MINECRAFT_VALKEY_DIR`, or
`MINECRAFT_VALKEY_SERVER` to override its port, data directory, or executable.

To run the Valkey terrain mode, set `ATLASNET_MINECRAFT_VALKEY_TERRAIN=1` in the
environment of the processes launching AtlasNet and configure a single shard
with an unbounded XZ region. The worker uses compressed vanilla chunk NBT at
`minecraft:v1:chunk:minecraft:overworld:<x>:<z>`. Valkey atomically fences
records by authority epoch and save version. Loaded full chunks receive an
AtlasNet entity at their horizontal center, and the worker releases that entity
after the chunk unload and final queued write. The mode uses a separate local
directory under `fabric/run/server/valkey-shards` for Minecraft's other files.
Use `save-all flush` in the dedicated server console before stopping the stack
to wait for outstanding terrain saves.

Player view changes route chunk demand through SpatialRPC. The recipient shard
uses the RPC's authority geometry to select chunk centers and updates vanilla
loading tickets by observer. Block break and placement Commands use Minecraft's
server-side packet handlers. Once a viewer acknowledges the initial chunk batch,
tracked block updates that fit a single AtlasNet Signal use Spatial Audience.
Initial chunks, unloads, and larger updates use Minecraft's viewer selection and
the existing directed packet stream.

The Stack Tool prelaunch prepares a shared terrain session for both workers.
For a manual launcher, set `ATLASNET_MINECRAFT_VALKEY_TERRAIN=partitioned` in
the AtlasNet node environment. Use a 2D heuristic with `cluster.spatialPlane`
set to `XZ`.
To exercise both owners, give the two ingress groups opposite
Overworld spawn positions, such as `-16:-60:0` and `16:-60:0`.
Chunks belong to the shard containing their XZ center, independent of height.
Nonowners can load read-only replicas for vanilla chunk dependencies. The owner
sends the initial authoritative chunk and subsequent block changes to remote
viewers through Spatial Signals. A creative player with an empty hand can
request an ordinary block break across the border through SpatialRPC.

To enable live chunk migration, give every node the same
`MINECRAFT_TERRAIN_SESSION` UUID and set AtlasNet's region transfer hysteresis
to zero. Use a new session UUID only after stopping every node from the
previous run. The UUID identifies which workers can still own chunks in the
shared Valkey database. With no session UUID, this mode requires a stable
allocation of chunk centers and stops if ownership changes.

Live migration uses AtlasNet's transfer callbacks and supports both axis-aligned
regions and KMeans2D's region planes.
The source freezes the chunk and reserves its saved revision in Valkey. The
destination installs the checkpoint, including block entities and scheduled
ticks, before resuming simulation. The transfer does not cover Minecraft's
separate entity or POI files. Dropped items and arrows use their own AtlasNet
entity lifecycles and transfer their vanilla entity state separately. Melee
against a remote player uses EntityRPC to resolve the hit on that player's
authoritative shard. Cross-shard block interactions that need inventory or
block-entity transactions remain unsupported.

The default Overworld uses normal generation with seed `3955`. Both ingress
endpoints spawn players at `-16:64:0`. The normal terrain store uses port
`46382` and `run/minecraft-valkey-normal`. The previous superflat terrain uses
port `46381` and `run/minecraft-valkey`. Set `MINECRAFT_TERRAIN_PRESET=flat`
explicitly when launching a superflat development world.

Run `./gradlew :fabric:chunkStorageTest` against an isolated Minecraft Valkey
with a fresh data directory. After stopping and starting that test instance,
run `./gradlew :fabric:chunkStorageTest --args=verify-restart` to check recovery
of both NBT and ownership metadata. The task also accepts
`--args="verify-region /path/to/r.0.0.mca"` for a real generated chunk.
