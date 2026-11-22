# AtlasNet ingress modules

These projects use the AtlasNet Module Java SDK. The admission module implements
Minecraft 1.21.1 online-mode Login and Configuration. The spawn resolver exposes
registration for a configured initial placement. The Minecraft JVM host registers the providers, including the Java TCP endpoint.

| Project | Execution context | Existing AtlasNet binding |
| --- | --- | --- |
| `atlasnet-endpoint` | Node / Client Ingress | `Registry.registerClientEndpoint` with `ClientEndpoint` and interactive admission |
| `atlasnet-admissionHandler` | AtlasNet ingress admission | `Registry.registerInteractiveAdmissionHandler` with `InteractiveAdmissionHandler`, the binding for `IClientAdmissionHandler` |
| `atlasnet-spawnResolver` | AtlasNet ingress spawn resolution | `Registry.registerSpawnResolver` with `SpawnResolver`, the binding for `IClientSpawnResolver` |

## Client lifecycle

```text
Minecraft client
  → atlasnet-endpoint
  → atlasnet-admissionHandler
  → atlasnet-spawnResolver
  → AtlasNet shard selection
  → atlasnet-plugin (vanilla shard runtime)
  → ShardAPI::OnClientSpawn
```

AtlasNet mediates each stage. The ingress endpoint owns TCP framing, encryption,
and compression. Admission verifies the Minecraft identity and finishes
Configuration. The resolver returns the initial AtlasNet world and position,
and AtlasNet invokes `OnClientSpawn` on the selected shard. The shard runtime
uses the vanilla dedicated server and routes gameplay through the packet bridge.

In the current native ingress path, admission runs during the AtlasNet Client
V1 handshake. Accepted admission bytes are passed to the spawn resolver unless
the listener has a static spawn configured. Handlers receive `onStart` and
finalize through `ClientAdmissionSession.complete`, including immediate
acceptance and rejection.
The upstream Java SDK provides `Callbacks.spawnResolver` to transfer ownership
of boxed spawn results. Callbacks run on the native calling thread, and the
host keeps the JVM and its providers alive for the module lifetime.

## Binding boundaries

The Java SDK exposes `Registry.registerClientEndpoint` and interactive
admission. The Minecraft admission handler runs on the ingress Node. It loads
the vanilla built-in pack through `WorldLoader` without a shard server, uses
Minecraft's packet codecs and registry synchronization task, and verifies the
encrypted Login exchange using Authlib's Minecraft session service. Only the
client's acknowledgement of Finish Configuration completes admission.

A module host can register `MinecraftSpawnResolver.DEFAULT` for the
temporary AtlasNet placement `minecraft:overworld` at `(0, 150, 0)`, or
construct the resolver with a different configured placement. AtlasNet uses that placement to select the shard.
The resolver performs no Minecraft world I/O. The shard uses vanilla player
placement, including saved positions for returning players.

AtlasNet currently loads native modules through `AtlasNetGetModule`. Its Java
SDK exposes the registry but does not supply JVM startup, Java JAR discovery,
or native plugin entrypoint glue. The module in `host/` supplies that glue in the Node and shard worker.

## Minecraft admission configuration

Edit `MinecraftAdmissionDefaults.CONFIG` in source and recompile. It specifies
the brand, network compression threshold, and vanilla datapack function
permission level. Defaults use the vanilla brand, a 256-byte compression
threshold, and permission level 2. The handler presents the normal vanilla
feature set, built-in packs, registries, and tags. Configuration has no external
file loading, custom resource packs, or shard-specific inputs. Deployment must
use the same vanilla version, registries, and content on all shards.

The native module host constructs and registers
`new MinecraftAdmissionHandler(MinecraftAdmissionDefaults.CONFIG)` during
startup. This loads Minecraft's resources once and requires no world or running
Minecraft server. The host closes the handler after ingress stops. Authlib
session verification runs off the ingress thread.

## TCP endpoint contract

The endpoint defaults to TCP port `25565`. It owns the protocol-767 Login handshake, frame lengths, encryption,
and compression. Submit one decoded packet body at a time through interactive
admission. Bodies include the vanilla packet ID and exclude the frame length,
compression metadata, and encryption. The initial admission payload carries the actual socket peer followed by the
`ServerboundHelloPacket` body. The peer is one address length byte (4 or 16),
the address bytes, and an unsigned 16-bit port in network byte order. Further
client bodies go through the admission ticket.

Endpoint-bound intermediate and final response bytes start with one internal
operation byte. The operation byte never goes on the Minecraft wire.

| Operation | Bytes after the operation | Endpoint action |
| --- | --- | --- |
| `0` | Vanilla packet body | Frame and write with the current transport state |
| `1` | 16-byte AES secret | Enable vanilla encryption in both directions before processing subsequent traffic |
| `2` | Vanilla VarInt threshold, then Set Compression packet body | Write the packet with the previous compression state, then enable the threshold in both directions |

`MinecraftTcpCodec` implements these operations with AES-128/CFB8,
VarInt21 framing, and vanilla zlib packet encoding. It consumes one packet
per read, leaving later bytes in the TCP input buffer for the caller to
apply transitions at packet boundaries. Its writer must transmit the entire
frame before returning and close the session on failure. In particular, the
Set Compression frame is written with the old framing and the new threshold
is installed after that write. The TCP endpoint applies the codec to each accepted connection.

Apply operations in order on the connection's event loop. After forwarding
the client's Finish Configuration acknowledgement, hold any Play traffic until
the final AtlasNet callback confirms the spawn. A success carries the verified
`ByteBufCodecs.GAME_PROFILE` value followed by the vanilla
`ClientInformation.write` value and the same trusted peer metadata in
`AdmissionResult.data`. The shard can use
these to initialize the authenticated player without replaying Login or
Configuration. In the worker, `MinecraftPlayerJoin.place` checks vanilla
player-list admission rules and calls `PlayerList.placeNewPlayer` on the server
thread. Vanilla installs its original `ServerGamePacketListenerImpl` on the
`AtlasNetConnection`. That connection uses the vanilla Play codecs and
carries packet bodies without TCP framing, encryption, or compression.
`MinecraftTcpEndpoint.register` opens the TCP listener when AtlasNet starts
the endpoint. Its Play input uses the existing raw Command instruction with
the unframed Minecraft packet body. Play output expects Signal ID
`0x4d43504b` with that same body format. `MinecraftShardBridge` binds AtlasNet's shard callbacks to the server-side
connection and emits client-targeted Play Signals. The standalone JVM host
still needs to start the Minecraft server, install the bridge, and drive
`tickConnections` each Minecraft tick. AtlasNet defers reliable Signals until the ingress
session is ready and checks the serialized size against the effective RPC
limit.

## Run a one-node stack

Build matching AtlasNet Node, shard worker, GObject and Java bindings first.
Set the Gradle properties below and point `JAVA_HOME` to JDK 25.

```sh
cmake -S . -B build -DATLASNET_MINECRAFT_HOST=ON
cmake --build build --target atlasnet-minecraft-host
```

In AtlasNet Local Stack, set the program to the absolute path to
`build/host/atlasnet-minecraft-host`. Configure a single group with count 1
and the Database, ControllerEligible, Shard, and ClientIngress capabilities.
Enable managed Valkey, use `atlasnet.kdtree2d` as the controller heuristic,
and add group arguments `--shards-max-count 1 --ingress-endpoints minecraft`.
The launcher supplies the Minecraft module through `--module`. Include any
additional modules with that same CLI option because AtlasNet CLI modules
override the YAML module list.

Set `ATLASNET_MINECRAFT_WORLD_DIR` before launching to choose the worker's
server directory. It defaults to `minecraft-shard` relative to the stack's
working directory. Prepare the server directory with your accepted Minecraft
EULA. It can also contain vanilla `server.properties` and world data. Once
the worker is ready, use Direct Connection to `localhost:25565` from a
vanilla Minecraft 1.21.1 client signed in to a Minecraft account.

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
includes the ingress projects. Existing named plugin and mod builds remain usable
with JDK 21 alone.

For the admission handler and shard plugin, also set `minecraftAdmissionJar` to a Minecraft 1.21.1 common/server JAR
in the official Mojang mappings namespace, including vanilla data resources.
Set `minecraftAdmissionLibraries` to the directory containing the matching
server dependency JARs, such as the unpacked `META-INF/libraries` directory
from the official 1.21.1 server bundle. The host must supply the same JARs at
runtime. These dependencies are compile-only and are kept out of the module JARs.

The dependencies are compile-only. The build does not generate bindings,
initialize native code, or bundle dependency classes. Running a provider will require the
matching AtlasNet and Java-GI JARs, JDK 25, the same-build
`libatlasnet-gobject.so` and its native dependencies, library search paths, and
JVM native access configured as described in the upstream SDK instructions.
