# AtlasNet Minecraft Demo — Agent Instructions

## Purpose of this repository

This repository is a **Minecraft Java Edition demonstration and integration test for AtlasNet**. Its purpose is to validate that an existing game can run its simulation across multiple AtlasNet shards and nodes while AtlasNet handles ingress, routing, entity authority, migration, spatial interest, and distributed messaging.

**This is not a generic Minecraft networking mod, a new Minecraft server implementation, or a production-ready public server.** Preserve Minecraft's existing game simulation, entity types, world logic, and client rendering wherever possible. Replace the networking/distribution boundary, not the game itself.

The primary proof is that multiple Minecraft server-side shard processes can simulate different authoritative entities, and Minecraft clients can continue to see and interact with those entities through AtlasNet—even when entities or players cross shard boundaries.

## Source of truth and where to read first

AtlasNet is the authoritative implementation of the distributed networking system. **Before editing this demo, inspect the live AtlasNet implementation and read the relevant documentation under `AtlasNet/Docs/`.** The relative path may depend on how the repositories are checked out; locate the AtlasNet source instead of assuming an unverified path.

Read the docs and then inspect code for:

- AtlasNet Node lifecycle, startup and configuration, shard-worker launch, and module APIs.
- Client API, Shard API, and any available language bindings.
- Client ingress transports, the client wire protocol, and the **SteamNetworkingSockets / SteamNetSock** ingress implementation.
- Client admission handlers and client spawn resolvers, including any currently implemented default behaviors.
- Commands (client → authoritative entity's shard) and Signals (shard → client).
- Entity registration, entity positions/transforms, authority, migration, and transfer coordination.
- `Audience::Spatial`, SpatialInterest Enter/Exit events, EntityRPC, SpatialRPC / `QuerySpatial`, and local/distributed spatial queries.
- Entity and client identifiers, ownership, reliability, ordering, and channel behavior.
- `integrations/GObject` and `integrations/java` if Java bindings are needed.

**Do not treat this document's examples as proof that a named method exists.** Follow the current code and documentation for exact signatures and behavior. If documentation and implementation conflict, report the discrepancy; do not silently invent an adapter API.

## What AtlasNet is

AtlasNet is a distributed simulation/networking framework designed for games and other systems in which entities are simulated on authoritative shards. A Node hosts selected capabilities, including client ingress and/or shards; a Controller coordinates cluster topology and authority policy; a shard owns and runs authoritative simulation for its entities. Shard workers can be separate processes.

Key concepts:

1. **Client ingress is stable.** A client connects to an AtlasNet ingress endpoint, rather than connecting directly to a Minecraft shard. AtlasNet routes messages as entity authority changes; players should not reconnect just because their player entity migrates.
2. **One client corresponds to one owned AtlasNet entity.** The client API intentionally does not expose arbitrary entity targeting. Its `DispatchCommand` applies to the entity it owns; it receives Signals addressed to that client about the world/game state.
3. **Commands** flow from clients to their authoritative shard and represent player intent/input. **Signals** flow from shard/game logic to selected clients and carry results or replication updates. Clients do not send Signals or receive Commands through the client API.
4. **Shard authority is exclusive.** Only the authoritative shard simulates and mutates an entity's canonical state. Other shard-local representations, if needed, are proxies and must not independently simulate authoritative physics/AI/gameplay.
5. **Migration** transfers entity authority between shards without changing the client's ingress connection. Preserve the existing transfer protocol, locks, state, and ordering guarantees; do not implement a second migration system.
6. **Distributed spatial routing** is reusable infrastructure, not Minecraft-specific. Spatial audiences select recipients for one dispatch; SpatialInterests maintain persistent overlap membership; EntityRPC invokes operations on authoritative entities; SpatialRPC targets spatially authoritative regions. These have distinct semantics.

AtlasNet's transport, cluster messages, and callbacks must be used as implemented, not approximated with ad hoc Minecraft sockets or an independent RPC protocol.

## Target Minecraft integration

- Target **Minecraft Java Edition 1.21.1 with Fabric** for the initial demo, unless the repository/build files specify a different agreed target. Prefer Java 21 for Minecraft compatibility, but verify the currently generated AtlasNet Java binding runtime requirements; if they require a newer JVM, test the actual supported combination rather than assuming compatibility.
- Keep the integration project organized around reusable game-independent logic and a Fabric-specific adapter where the repository already supports that separation. NeoForge may be a later target, but **do not implement it now**.
- Reuse Minecraft's built-in entity representations, renderers, simulation, physics, packets/data structures, chunk logic, and game behaviors where feasible.
- Favor a small, testable proof-of-concept over a broad compatibility layer for all vanilla and third-party packet types at once.

### Networking requirement: AtlasNet replaces Minecraft's game network path

**Completely bypass Minecraft's built-in client/server socket transport for this demo's gameplay connection.** The client and server must exchange gameplay traffic through the AtlasNet Client API / wire protocol and AtlasNet's ingress transport, **initially using AtlasNet's existing SteamNetSock implementation**.

Do **not** open or retain a parallel vanilla Minecraft TCP gameplay connection. Do not create a second custom raw socket protocol beside AtlasNet. Avoid accidental fallback to Minecraft's networking if AtlasNet transport fails.

The Minecraft client-side adapter should intercept the relevant outgoing Minecraft game actions/packets, serialize them into AtlasNet Commands, and deliver incoming AtlasNet Signals to the appropriate Minecraft client-side handlers on the correct game thread. The server-side adapter should decode Commands into the correct authoritative Minecraft player/entity context and convert authoritative server-generated game updates into Signals, preserving their intended recipients.

A possible direction is to adapt/intercept Minecraft's Netty pipeline and packet processing boundaries while keeping packet codecs and vanilla handlers where practical. **Do not assume unchanged vanilla packets can simply be injected without considering connection state and game-thread semantics.** Prefer reusing tested Minecraft codecs and handlers rather than rewriting rendering or world logic.

The transport-level SteamNetworkingSockets connection establishment required by AtlasNet still applies. Removing Minecraft's protocol login/handshake must **not** remove AtlasNet's necessary transport/session setup.

### Minecraft login, handshake, and authentication — demo scope

For the initial demo, **bypass the vanilla Minecraft handshake, login-state negotiation, online-mode authentication, and Minecraft session verification** on the AtlasNet-backed gameplay path. These vanilla flows are not the authority for this demo's AtlasNet transport.

Instead, establish the minimum connection-to-player context needed for Minecraft to operate through AtlasNet ingress, then spawn/register the associated AtlasNet player entity using the existing temporary/default admission and spawn behavior supported by AtlasNet.

Eventually, connection validation/identity will be implemented through an AtlasNet **`IClientAdmissionHandler`** and spawn/initial placement will be implemented through **`IClientSpawnResolver`** (or their current canonical equivalents). **Do not build a new Minecraft-specific permanent authentication system or invent those integrations now.** If these hooks already exist, inspect whether they can be reused as-is without changing scope.

This bypass is **for a controlled development/demo environment only**. Do not represent unauthenticated clients as verified Minecraft/Microsoft identities, do not trust client-provided player identities for authorization, and do not describe the result as suitable for an untrusted public server. Keep the future admission boundary explicit rather than burying identity assumptions in game logic.

## Player and entity mapping

- Each connected Minecraft player must map to one AtlasNet client-owned entity, registered on its authoritative shard.
- Maintain consistent mappings between AtlasNet entity/client identity and Minecraft player/entity identity, without assuming the identifier formats are interchangeable.
- Player input travels as Commands from the client's stable ingress connection to the currently authoritative shard.
- Authoritative world/entity changes become Signals to interested clients.
- On authority migration, continue accepting input through ingress and restore game state on the destination shard without requiring a vanilla reconnect.
- Keep remote entities and players representable to the Minecraft game engine when needed, but ensure nonauthoritative proxies do not execute AI, physics, or gameplay mutations.
- Cross-shard interactions should use existing EntityRPC or SpatialRPC primitives as appropriate; do not blindly mutate a remote proxy and assume the owning shard will converge.

## Spatial visibility and replication

Use the spatial tools according to their intended semantics:

- **`Audience::Spatial`** is a one-shot recipient selector: route the original spatial selector to relevant Nodes, then resolve locally authoritative matching client-owned entities. It must **not** execute EntityRPC for each Signal recipient or create EntityRPC receipts for routine movement updates.
- **SpatialInterest** is a persistent distributed overlap subscription. For player visibility, an interest attached to an observing player's entity determines which other entities that player should currently see. The observing client receives **Enter → initial spawn/full state**, **ongoing updates**, and **Exit → remove/despawn**. Both the interest and observed entities may move or migrate.
- **EntityRPC** is for game-defined operations on authoritative entities, including cross-shard interactions; it is not the underlying mechanism for sending ordinary spatial movement Signals.
- **SpatialRPC / `QuerySpatial`** is for operations on spatially authoritative regions, such as world/terrain operations, and must not be confused with finding existing entities inside simulation bounds.

Respect existing batching, sampled-interest detection, audience routing, reliability, ordering, authority revisions, and migration semantics. Do not add constant global entity scans, broadcast all movement to all clients, or create perpetual per-recipient receipts.

### Client entity lifecycle

The client must learn about an entity's existence **before** receiving dependent updates; when it leaves the player's interest, it must remove that visualization and ignore stale updates for the old visibility generation. Re-entry must initialize fresh state. Stationary entities must appear upon initial interest entry, not only after moving. The local player must not appear as an unwanted duplicated remote entity.

## World and terrain scope

The desired demo should eventually show multiple authoritative Minecraft shards operating in one logical Minecraft world and loading/saving shared terrain through an external database/storage abstraction rather than treating one server process as the permanent world owner.

Start with **superflat** for initial bring-up and reduce unrelated world-generation complexity. Inspect the existing AtlasNet World support before choosing an ownership mapping: the AtlasNet World system may still be incomplete or use placeholder IDs. Do not invent a full AtlasNet World implementation as a prerequisite.

Cross-shard block updates, chunk ownership, block entities, redstone, collisions, and other shared-world behavior are real correctness challenges. Make any deferred limitations explicit rather than claiming seamless distributed Minecraft simulation before they are tested.

## Milestones / desired proof

1. A Fabric Minecraft client establishes its gameplay connection through AtlasNet **SteamNetSock ingress**, with no vanilla gameplay socket, vanilla handshake, or vanilla authentication flow.
2. The client is associated with one AtlasNet-controlled player entity and can send movement/input Commands; the authoritative server-side shard receives them.
3. Vanilla Minecraft client rendering and server-side game logic continue to function through the adapter for the supported packet/action subset.
4. Multiple players see one another through Signals, with spawn/update/remove driven by SpatialInterest and appropriate audiences.
5. Players/entities can be hosted by distinct shards/Nodes; player movement and entity authority migration do not require reconnecting to another game server.
6. Demonstrate at least one cross-shard interaction via the appropriate existing AtlasNet operation.
7. Extend toward shared chunk/terrain persistence only after the transport, identity mapping, replication, and migration baseline is verifiably working.

Do not claim later milestones are complete because the initial connection succeeds.

## Engineering and change-control rules

- **Read `AtlasNet/Docs/` and the current source before proposing architecture or implementing bindings.** Check available APIs and follow actual type signatures.
- This file records desired behavior, not permission to redesign AtlasNet's core architecture. Prefer minimal adaptations and existing AtlasNet extension points.
- The project owner retains final architectural authority. Before creating, deleting, renaming, or modifying files outside the explicitly authorized task scope, explain the need and obtain approval. In particular, do not silently modify AtlasNet core APIs or wire formats.
- Do not add polling loops where event-driven AtlasNet Node / IntentRPC / callback paths already exist.
- Do not use EntityRPC as a generic replacement for Signals or Spatial Audience routing.
- Do not build a parallel client transport, persistent identity database, bespoke shard coordinator, or extra RPC protocol.
- Respect game-thread confinement, shard ownership, and asynchronous network callback safety. Never call Minecraft world/entity APIs from an arbitrary network thread.
- Preserve existing migration and exactly-defined channel reliability semantics; do not promise impossible atomicity or exactly-once behavior without supporting machinery.
- Log and report unsupported vanilla packet flows and incomplete gameplay cases clearly; do not silently drop them while claiming full compatibility.
- Add targeted tests for codecs/bridges, player/entity identity mapping, command/signal routing, SpatialInterest visibility, and authority handoff. Build and run tests; report actual results.
- Keep documentation updated whenever a decision is confirmed. Distinguish implemented functionality from plans and placeholders.

## Before each task

1. State which behavior is being implemented and its success criteria.
2. Read the relevant `AtlasNet/Docs/` pages and concrete implementations.
3. Identify the exact files and APIs involved, and any required changes to AtlasNet itself.
4. Keep modifications limited to the authorized scope; raise architecture or security ambiguities instead of guessing.
5. Validate the feature with the supported Minecraft/Fabric and AtlasNet builds and provide a concise file-by-file report.

**Overall goal:** Demonstrate AtlasNet as the networking, ingress, distribution, and authority layer underneath a recognizable Minecraft game experience—not to make Minecraft's original networking coexist with AtlasNet.
