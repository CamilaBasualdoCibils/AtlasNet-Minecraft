# AtlasNet Minecraft architecture

The active SDK defines platform-independent contracts for extensions. The
archived proof of concept in `legacy/` keeps its original design for reference.
The production foundation below introduces optional transport upgrades while
preserving the vanilla Minecraft path.

## Module dependencies

Arrows point from each dependent project to its direct prerequisite.

```text
atlasnet-mod/client ────► common/mod ───► common/core
                              ▲                ▲
                              │                │
atlasnet-mod/server ──────────┘                │
         └──────────────► atlasnet-plugin ─────┘
```

- `common/core` provides a Minecraft resource key with no project dependencies.
  Future transport-facing adapters should use canonical AtlasNet SDK types for
  AtlasNet identities and wire formats.
- `common/mod` exposes shared content declarations keyed by `ResourceKey` and a
  registration-phase interface. A developer can declare content in shared code
  and present the same definitions to both loader sides.
- `atlasnet-plugin` exposes server contracts using the core values. Its entity
  ID is a logical Minecraft-facing handle. Mapping it to an AtlasNet entity is
  adapter work. Public references are independent of loader classes.
- `atlasnet-mod/client` exposes client content registration using the shared mod
  API. The dependency path ends at `common/core`.
- `atlasnet-mod/server` extends the server plugin lifecycle with mod registration
  and a server-facing content synchronization contract.

The common, plugin, and mod SDKs target Java 21. The three
[ingress module scaffolds](atlasnet-modules.md) use Java 25 with the prebuilt
AtlasNet Java-GI SDK. The Minecraft build consumes the binding JARs at compile
time. Selecting and testing the JVM and native binding combination for future
runtime adapters remains open.

## Future platform adapters

| Platform | Planned integration |
| --- | --- |
| [Paper](../platforms/paper/README.md) | Server plugin with vanilla clients and optional AtlasNet transport upgrades |
| [Fabric](../platforms/fabric/README.md) | Server plugin, dedicated-server mod, and client mod |
| [NeoForge](../platforms/neoforge/README.md) | Server plugin, dedicated-server mod, and client mod |

The adapters will translate loader lifecycle, execution contexts, and
Minecraft world and entity behavior into the shared contracts. AtlasNet will
provide native ingress, authority routing, and shard migration. Platform
adapters will retain responsibility for translating game operations and
confining game-state work to the platform's execution contexts.

## Planned transport negotiation and admission

An ordinary Minecraft server continues to use vanilla Minecraft TCP, including
when the client has the optional AtlasNet mod installed.

```text
Client with optional AtlasNet mod → vanilla Minecraft TCP → ordinary server
```

At an AtlasNet-enabled server, an enhanced client starts with Minecraft's normal
connection and discovers support for an upgrade. The AtlasNet Client API will
establish the native session with AtlasNet ingress. Vanilla clients continue
through the standard Minecraft path when server policy allows. Both paths feed
the same authoritative simulation.

```text
Initial Minecraft connection → AtlasNet capability discovery
  → native AtlasNet session → AtlasNet ingress → distributed Minecraft world
```

The client and server must agree on which transport is responsible for each
traffic flow during the upgrade so a gameplay action is processed once. The
upgrade must preserve the player's authenticated identity and authoritative
entity, and AtlasNet must route native Commands as authority migrates. Native
delivery modes and future socket switching, dual-socket, and P2P capabilities
belong to AtlasNet. The negotiation messages, authenticated identity binding,
handoff timing, and failure recovery remain design work.

The following configuration is reserved for the server plugin:

```yaml
client:
  atlasnet-clients-only: false
```

With `false`, both vanilla and AtlasNet-enhanced clients can join under the
server's normal admission rules. With `true`, the server must verify a
compatible AtlasNet client and an established native AtlasNet session before
admitting the player to the simulation. This setting is documentation only.
