# AtlasNet ingress modules

These projects use the AtlasNet Module Java SDK. The admission module implements
Minecraft 1.21.1 Login and Configuration, with online mode by default. The spawn resolver exposes
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
construct the resolver with a different configured placement. The JVM host
uses a returning player's admission checkpoint for initial shard selection
when it supplies a position. The shard restores that checkpoint in vanilla.
For a new player, the shard passes the configured placement to vanilla.
The resolver reads the admission payload without doing Minecraft world I/O.

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
these to initialize the admitted player without replaying Login or
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
cmake --build build --target atlasnet-minecraft-modules
```

The checked-in AtlasNet Local Stack profile runs the standard
`AtlasNet/build/AtlasNetNode` executable. Its Minecraft group lists
`libatlasnet-minecraft-shard.so`, `libatlasnet-minecraft-endpoint.so`,
`libatlasnet-minecraft-admission.so`, and `libatlasnet-minecraft-spawn.so`
from `build/host` in the Node's `modules` setting. The modules register
through the standard AtlasNet module entry point and share a JVM per process.
Shard-only Nodes need only the shard module. The group runs one Node
with Database, ControllerEligible, Shard, and ClientIngress capabilities.
The acceptance profile requests two Minecraft shards for one PostgreSQL-backed
superflat world named `entity-acceptance`. It loads the `example.xsplit2d` module so authority crosses
the X=0 plane, subject to AtlasNet's hysteresis. The loopback Control Panel
can verify the player retains one AtlasNet EntityID while its shard changes.
Existing `world` directories and their checkpoints are preserved. It uses managed Valkey and the `minecraft` ingress endpoint.

Set `ATLASNET_MINECRAFT_WORLD_DIR` before launching to choose the worker's
primary server directory. It defaults to `minecraft-shard` relative to the
stack's working directory. Prepare it with your accepted Minecraft EULA and
any existing vanilla world data. Additional workers reserve siblings such as
`minecraft-shard-2` and inherit the primary directory's EULA acceptance when
first created. Entity checkpoints use the same logical PostgreSQL world key. Edit `clusterSettings.application.minecraft.server-properties` in the Local Stack cluster settings and restart the stack to apply changes. The host uses vanilla property parsing. Admission uses the same compression and function permission settings as the shard, and the shard sends its configured view distance in vanilla Play packets. Online-mode authentication is required. Once
the worker is ready, use Direct Connection to `localhost:25565` from a
vanilla Minecraft 1.21.1 client signed in to a Minecraft account.

The hosted server exposes the Overworld as the only gameplay destination.
Vanilla Nether and End portals cannot resolve a destination. The dimension
registries and Login and Configuration packets still include the vanilla data.

### Chunk subscribers

Each worker uses the named `minecraft.chunks` StateStore. A set named
`<dimension>/<chunk-x>/<chunk-z>/subscribers` holds canonical shard UUID
strings. For example, the Overworld chunk at `(5, 8)` uses
`minecraft:overworld/5/8/subscribers`. Membership follows vanilla's
full-chunk accessibility callback. The adapter delegates to the original
listener and sends StateStore updates on the AtlasNet worker thread. A chunk
that ceases to be accessible is removed from the set even if Minecraft still
has a cached representation while unloading it.

Membership describes which shards maintain a full chunk. It grants no
authority to tick that chunk. On graceful shutdown, the worker submits
removals through the existing shutdown callback and continues processing
RPC replies for up to four seconds from receipt of the shutdown signal. The
Node allows five seconds before forcing worker exit. In a multi-Node stack,
stop shard-only Nodes while the Database Node is still available. An unexpected
process exit or an unavailable StateStore during shutdown can leave stale members.
A future cleanup task can use the AtlasNet shard registry to remove members
whose shard instances have expired.

### Terrain integration testing

For a local two-client run, set `minecraft.server-properties.online-mode` to
`false` in the isolated profile and choose two distinct offline usernames.
The admission handler accepts offline identities only over loopback and derives
each UUID from its username using vanilla's offline profile algorithm. The
checked-in profile uses online mode.

The checked-in Run Stack profile sets
`clusterSettings.application.minecraft.terrain-enabled` to `true` for
PostgreSQL chunk I/O and AtlasNet chunk write fences. This gates vanilla block changes and terrain
ticks by local chunk authority. After PostgreSQL commits a revision, the
worker uses the existing chunk subscriber set and ShardRPC to refresh loaded
replicas. Replica block state and block-entity changes use vanilla's chunk
update notifications. The publishing worker retries failed subscriber lookups
and notice deliveries with bounded backoff. A failed replica refresh retries
while the chunk remains subscribed. The Minecraft thread discards refreshes
superseded by a newer request or a subscription change. The worker saves changes to vanilla block
and fluid tick queues. Authority activation restores those queues from the
committed chunk before enabling terrain simulation. A local two-shard test
with offline protocol clients verified creative edits across the X=0 split,
including a placement against a block face in the neighboring shard, and
confirmed that the second client received the corresponding block update.
With immediate transfers and zero hysteresis, the same two-shard regression
also verified that a queued break waits for a failed player checkpoint to
recover and that losing the first committed RPC reply adds no duplicate
terrain receipt.
After restarting the stock node with two new shard workers, fresh world
directories, and an empty Valkey instance, a newly connected offline client
received both committed blocks at X=-1 and X=0 in vanilla chunk packets.
The profile reads terrain from PostgreSQL and generates missing chunks.
Edits saved only in a worker's local world files before shared terrain was
enabled require a separate import.

If a PostgreSQL connection loses a save reply, the shard checks the stored
revision, chunk NBT, and authority fence before retrying. An already committed
snapshot keeps its original revision. A newer edit can then use that revision
as its write base.

For ordinary creative block placement and breaking on a remote chunk, the experimental
profile routes a point SpatialRPC to the chunk region, checks the write fence
on its Minecraft game thread, and executes vanilla `BlockItem.place` or
`ServerPlayerGameMode.destroyBlock`. For ordinary single-block placement,
the destination is the chunk that will contain the new block, which can differ
from the chunk containing the clicked face.
If the owner has unloaded the target chunk, it requests a full chunk load
through vanilla's asynchronous entry point and rechecks authority on the
Minecraft thread before executing the edit.
The source sends the client's block correction and acknowledgment when the
destination returns the result after saving. PostgreSQL records the response
and terrain revision in the same transaction. Duplicate request IDs can
reuse the recorded response. The chunk owner can also record a definitive
rejection without changing the terrain revision. When a reply is missing,
the source asks the owner to settle the same request ID. The owner reuses an
existing receipt, waits for execution already in flight, or records a no-op
for an action it never started. A failed interaction write retries with its
original receipt. Later terrain writes and authority handoff wait for that
transaction, and later player interactions wait for a confirmed result.
A durable per-player request record also prevents a new session from
starting a second remote edit before the previous request is settled.
The shard begins recovery when AtlasNet attaches the player. It reads the
existing reservation without creating one and queues block interaction packets
until any earlier creative edit is settled.
If the reservation write fails, the source retries with the same request ID
and holds later interactions behind it. If routing returns without a result,
the source retries the original RPC under that request ID before settling.
The source keeps its packet queue
with the attached player instance so a callback from a detached session
cannot remove a replacement session's queue.
The source acknowledges handling the result before PostgreSQL releases the
reservation. A late acknowledgment leaves a newer reservation intact.
For a confirmed edit on the live player, the shard uses the player checkpoint
queue to commit the fenced checkpoint and release the terrain reservation in
one PostgreSQL transaction. Failed settlements retain their place in the queue.
A reservation can carry
immutable vanilla NBT for recovering player effects. PostgreSQL retains that
state until settlement runs with a player checkpoint, even when the terrain
receipt already exists. The survival interaction path still needs to supply
and recover this state. Interactable blocks and operations that modify multiple
chunks still need interaction handoff support.

A container can currently open against a shard's loaded terrain replica.
Vanilla binds that menu to the local block entity, so clicking its slots does
not update the authoritative inventory. A two-client boundary test reproduced
this after both clients received the same committed chest placement.

The inventory backend can read vanilla container slots through EntityRPC.
Persistent block inventories use the chunk EntityID from StateStore and the
block's dimension and position. The receiving shard validates the active
chunk fence and reads the loaded vanilla block entity on its game thread.
Players and ordinary entities implementing vanilla `Container` use their
own EntityIDs. Block entity data continues to persist with the chunk.
Reads leave unopened loot tables deferred. Remote menu sessions, inventory
mutations, and transfers between inventory owners still need implementation.

Player visibility sends initial Play packets directly from the saved profile,
rotation, inventory, and pose when Spatial Interest reports an entry. This
keeps vanilla's UUID-indexed advancement tracker bound to the live player,
which receives any recipe rewards triggered by its inventory changes.

The X-split profile selects immediate region transfers with zero hysteresis.
An ordinary entity publishes its first transferable snapshot before it starts
ticking. This lets a fast entity transfer as it crosses the boundary, then
continue its vanilla simulation on the destination. In the two-client test,
an arrow launched at X=-5.5 transferred at X=0.44 with its velocity intact,
and vanilla reduced the Survival target's health from 20 to 10 at X=5.5.
This test covers that crossing and target placement. Shooter attribution
across shards and collision along a path that spans both regions in a single
tick still need verification.

### What Run Stack builds

The profile's **Prelaunch command** invokes `scripts/minecraft-build.sh stack`.
It builds the configured AtlasNet Node, worker, generated Java bindings, and
Valkey in `AtlasNet/build`, then builds the four Minecraft modules. It starts
PostgreSQL after the builds succeed. Local Stack waits for the prelaunch command
before launching Valkey and the Node.

The build picks JDK 25 and the Java-GI runtime JARs from the configured
AtlasNet build when available. It can reuse the Mojang-mapped Minecraft
1.21.1 common JAR and server libraries from the local Fabric Loom cache.
Set the Gradle properties below if you provision those dependencies elsewhere.
An existing accepted EULA from the local legacy test server can seed a new
worker directory. Otherwise the worker directory must already contain your
accepted EULA.

Docker must be accessible to the prelaunch task. PostgreSQL defaults to
`127.0.0.1:45432`, database and user `minecraft`, and development password
`minecraft-dev`. Override these with `ATLASNET_POSTGRES_PORT`,
`ATLASNET_POSTGRES_DATABASE`, `ATLASNET_POSTGRES_USER`, and
`ATLASNET_POSTGRES_PASSWORD`.

The service uses a Docker volume named for this checkout. Set
`ATLASNET_POSTGRES_VOLUME` to select a different volume. Local Stack runs the
prelaunch command once and has no corresponding shutdown command. To stop
PostgreSQL after stopping the stack, run `scripts/minecraft-postgres.sh stop`.
This removes the container and preserves the volume. Minecraft uses this
database for entity checkpoints.

## Compile against the AtlasNet SDK

Install JDK 25 for the Minecraft host and AtlasNet Java bindings, and JDK 21
for Java 21 SDK targets. Build or obtain the generated `atlasnet-1.0.jar` from the matching AtlasNet
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
includes the ingress projects. The standalone client mod build remains usable with JDK 21.

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

### Entity snapshots and checkpoints

The shard starts publishing a player's snapshot after it receives the first
AtlasNet-routed Play command for the player. That command arrives after the
Node has accepted the spawn. The bridge samples the current vanilla
`ServerPlayer` on the Minecraft thread every 20 ticks. The worker coalesces
samples and writes changed snapshots to the named `minecraft.entities`
StateStore, keyed by the unsigned AtlasNet EntityID. Conditional writes
compare against the previous published record. A comparison failure stops
publication for that EntityID. A backend error leaves the latest sample
pending for retry, including while the entity is retiring. Retirement and graceful shutdown remove
records only if they still match the worker's last published value.

Snapshots contain vanilla persistent NBT. Changed player snapshots are checkpointed to
the Local Stack PostgreSQL database. Set
`application.minecraft.entity-store.persistence-interval-ms` in cluster
settings to change the five-second interval. Disconnect requests a final
checkpoint. On a graceful shard stop, the Minecraft thread captures final
samples and the AtlasNet worker services asynchronous completion within its
existing four-second deadline. PostgreSQL can commit the final local sample
if StateStore communication has already stopped during a whole-node stop. The admission worker retrieves the latest committed checkpoint
after verifying the Minecraft profile. The shard supplies it to vanilla's
player-data loader before the player joins. Vanilla player files remain the
fallback for profiles with no checkpoint. A checkpoint can lag live state
if a worker exits before its next write. During player transfer the
destination attaches a restored player to the existing Play session during
the migration commit. It preserves the vanilla
player entity ID and suppresses the join and initial teleport packets.
Both workers need matching terrain for the same coordinates to look and
behave the same after a handoff.

For ordinary entities, the Minecraft 1.21.1 lifecycle observer gates ticking
until AtlasNet registration and checkpoint restore finish. A transient
PostgreSQL read failure keeps the entity inactive and retries the restore.
Startup indexes
durable chunk coordinates. When vanilla loads one of those chunks, the
worker also reconstructs checkpointed entities absent from the local
entity file. The existing UUID reservation gates activation when multiple
workers load the same chunk. Startup also derives coordinates for older checkpoints from their saved NBT. PostgreSQL fences writes from an older
runtime ownership generation even when a handoff keeps the EntityID.
Unloading requests a final checkpoint. Permanent removal writes a PostgreSQL tombstone. Checkpoint
failures leave the revision pending for retry while the worker is running.

The transfer adapter uses AtlasNet's existing prepare, commit, and abort
callbacks. The source freezes vanilla ticking and captures
NBT. The destination stages a detached entity, conditionally installs a new
StateStore generation, then adds the entity to its level with the original
AtlasNet EntityID and Minecraft UUID. Preparing a transfer for a mounted
entity or a vehicle with passengers currently fails. Live cross-shard
handoff still requires verification with the Local Stack profile.


Operators can obtain a locally authoritative entity's ID with
`/atlasnetentity id <selector>` and read its revision, UUID, and active
effects from any shard with `/atlasnetentity read <entity-id>`.
Use `/atlasnetentity effect <entity-id> minecraft:glowing 1200 0` from
another shard to ask the owner to apply the effect via EntityRPC. Read
the record again to confirm publication of a newer revision. For a
single-account remote player test, run `/atlasnetentity probe <entity-id> 30`
on the current owner, cross the X=0 boundary, and remain on the other shard.
The initiating shard logs the before and after revisions, direct-write
rejection, and the resulting Glowing effect. A successful player probe also
sends a vanilla chat message directly from the initiating shard by Signal,
even after the player's authority has moved.


### Spatial API boundaries

Use an entity-attached Spatial Interest for persistent nearby-entity
membership and existing enter/exit notifications across shards. Use a world
interest for fixed regions. Read observed entity snapshots through
`AtlasNetEntityStore` and route authoritative changes through EntityRPC.
Spatial Interest controls the lifetime of one vanilla player representation,
using the same Minecraft entity ID on either shard. The authoritative shard
sends movement, look direction, and hand swings through AtlasNet
`DispatchSignal` with a spatial audience. Nearby clients use that same
delivery path regardless of which shard owns them. The endpoint skips the
originating client, which already renders its own player.
When the observer migrates, the Minecraft handoff carries the stable AtlasNet
interest handle and the player entity IDs the client already sees. The
destination rebinds the callback to the transferred membership and retains
existing client representations for players still in view.
The shard sends a cluster-wide Broadcast Signal for a real session join or
leave. Internal worker attachment and detachment during migration stay silent.
Player chat uses the same Broadcast audience after vanilla validates the
incoming message. The relay applies the filter mask and emits vanilla
server-delivered chat, which does not carry a player signature.
AtlasNet retains ordered input during the freeze.
Minecraft drains received packets in order on the game thread, limiting
movement packets per tick so the accumulated input still goes through
vanilla's usual movement checks.
The checked-in profile puts both shard workers on one node. In the two-node
test, the observer received continuous movement on the same player entity
as the player crossed between nodes. The observer could then cross and keep
the existing representation. Cross-node handoff requires matching AtlasNet
builds and the same heuristic module on each node.
A spatial EntityRPC selects matching authoritative entities for one call.
SpatialRPC instead routes to the owners of intersecting regions, including
regions without entities. Use Signals to deliver results to clients.
