# Fixed-plane migration validation

Validated locally on 2026-11-17 with Minecraft 1.21.1, Fabric, Java 21, and
AtlasNet's module ABI 11. AtlasNet source and its X-axis example were reused
unchanged. No SimpleGame client or server was used.

The saved Stack Tool configuration starts two nodes with one Minecraft shard
each. Both load `AtlasNetXAxisSplit.so` and select `example.xsplit2d`, dimension
2, spatial plane XZ. Its actual boundary is x=0. The superflat ingress spawns
are (-12,-60,0) on port 5919 and (12,-60,6) on port 5920. `BuildServer` builds
the heuristic module before the stack starts.

## Changes

| File | Change |
| --- | --- |
| `.vscode/atlasnet-stack.json` | Load the registered X-axis heuristic on both nodes; configure flat terrain, opposite-side spawns, zero hysteresis, and 50 ms transfer batches. |
| `native/CMakeLists.txt` | Build the upstream example in the Minecraft build tree with C++23; make it a `BuildServer` dependency and resolve the SDK source in standalone or parent builds. |
| `scripts/minecraft-stack-gdb` | Default to the flat terrain database while preserving explicit terrain and port overrides. |
| `native/MinecraftShardModule.cpp` | Publish poses immediately on sequenced stream 3, confirm settled poses reliably, retain movement sequence across migration, and wait for fresh destination motion before publishing. Republish terrain demand without forcing a full refresh. |
| `native/FabricServerHost.hpp` | Carry measured motion and movement sequence through the existing Minecraft-owned JVM bridge. |
| `native/SteamClientBridge.cpp` | Send Commands immediately while retaining their reliable ordering. |
| `common/src/main/java/net/atlasnet/minecraft/atlasnet/protocol/signals/PlayerPoseSignal.java` | Extend the shared pose payload with velocity in blocks per tick and the existing client movement sequence. |
| `common/src/protocolTest/java/net/atlasnet/minecraft/atlasnet/protocol/ProtocolRegistryTest.java` | Check the native wire layout, motion fields, truncation, invalid velocity, and invalid sequence. |
| `fabric/src/main/java/net/atlasnet/minecraft/fabric/server/ShardPlayers.java` | Reuse the client's recipes and command tree during handoff; measure replay-aware velocity; suppress pre-acknowledgement checkpoint poses and retain acknowledged chunk deliveries. |
| `fabric/src/main/java/net/atlasnet/minecraft/fabric/server/ChunkDemandManager.java` | Preserve cached terrain across local/remote viewer transitions, while allowing unacknowledged chunks to be sent. |
| `fabric/src/client/java/net/atlasnet/minecraft/fabric/client/AtlasNetPlayConnection.java` | Preserve owner snapshot assemblies across player play epochs in the same world/session. |
| `fabric/src/client/java/net/atlasnet/minecraft/fabric/client/RemotePlayers.java` | Retain vanilla interpolation and bridge short snapshot gaps with bounded visual prediction. Reconcile late source samples using movement sequence without rewinding the rendered trajectory. Remote replicas do not gain simulation authority. |
| `fabric/build.gradle.kts` | Add the optional `atlasnetMigrationTest` source set and test mod, excluded from normal launches and artifacts. |
| `fabric/src/migrationTest/java/net/atlasnet/minecraft/test/MigrationProbe.java` | Drive real movement keys, aim the stationary observer, record client state and received poses, and capture screenshots. |
| `fabric/src/migrationTest/java/net/atlasnet/minecraft/test/mixin/ClientMotionMixin.java` | Sample the real Minecraft client tick. |
| `fabric/src/migrationTest/java/net/atlasnet/minecraft/test/mixin/ClientPacketsMixin.java` | Observe pose receipt and whether a handoff applied a position correction. |
| `fabric/src/migrationTest/java/net/atlasnet/minecraft/test/mixin/ServerTimingMixin.java` | Record server ticks over 100 ms and tick gaps over 150 ms. |
| `fabric/src/migrationTest/java/net/atlasnet/minecraft/test/mixin/PlayerHandoffTimingMixin.java` | Record handoff callback timings and pose production. |
| `fabric/src/migrationTest/resources/fabric.mod.json` | Declare the optional test mod. |
| `fabric/src/migrationTest/resources/atlasnet_migration_test.mixins.json` | Separate server and client measurement hooks. |
| `tests/integration/prepare-migration.cjs` | Generate the two-node configuration through the actual AtlasNet Stack Tool using isolated test ports. |
| `tests/integration/migration.py` | Build and launch the stack and two graphical Fabric clients; enforce migration acceptance checks, stop owned processes, and restore the normal launch configuration. |
| `README.md` | Document the fixed boundary, database selection, launch configuration, and reproducible test. |

The delivery backlog was reproduced before the fix: the observer could freeze
for about 1.4 seconds. Removing redundant login snapshots helped, but the
remaining terrain refresh still delayed pose delivery after the shard was
already producing updates at 20 Hz. Retaining the terrain cache removed that
backlog. The longer-batch test also exposed late source samples rewinding a
predicted replica; sequence-aware reconciliation addresses that case.

## Final test results

Each run used two actual Fabric clients on separate SteamNetSock ingress
sockets, 5971 and 5972. Test ports and databases are isolated from the saved
stack. One client walked repeatedly between x=-32 and x=32; the second stood
aside, looked at the walker, and enabled the region outline. Success required
actual changes to the authoritative shard UUID, rather than crossing x=0.

| Measurement | `final-walking` | `final-batched` |
| --- | ---: | ---: |
| Transfer batch interval | 50 ms | 400 ms |
| Measured duration | 90.004 s | 90.004 s |
| Confirmed shard changes | 6 | 6 |
| Applied handoff position corrections | 0 | 0 |
| Walker backward steps | 0 | 0 |
| Observer backward steps near handoff | 0 | 0 |
| Observer boundary stalls | 0 s | 0 s |
| Missing/recreated remote players | 0 | 0 |
| Loading screens / world replacements | 0 / 0 | 0 / 0 |
| Largest observer step near handoff | 0.216 blocks | 0.288 blocks |
| Largest walker client tick gap | 61.93 ms | 65.36 ms |
| Largest observer client tick gap | 62.74 ms | 61.53 ms |
| Server ticks over 100 ms during measurement | 0 | 0 |
| Server tick gaps over 150 ms during measurement | 0 | 0 |

Reports, logs, pose traces, and screenshots are in
`build/migration-test/final-walking/` and `build/migration-test/final-batched/`.
Startup and world-loading time precede the measurement window.

Both CMake build targets succeeded. `:common:check`, including the shared
protocol codec tests, and `:fabric:check` passed. Normal shard launch files and
Fabric jars were verified to exclude the test mod. Test services were stopped.

The additional `final-recovery` run used 400 ms batches for 35 seconds and
completed three more migrations with the same acceptance checks. Its observer
deliberately omitted resting updates, then recovered a matching revision from
the reliable confirmation. Confirmations use unordered delivery so ingress's
sequence filter cannot discard them after sending the original datagram;
the application's pose revision still rejects stale or duplicate state.

To repeat from a graphical session with the AtlasNet SDK built:

```sh
python3 tests/integration/migration.py --seconds 90 --label walking
MIGRATION_BATCH=400ms python3 tests/integration/migration.py --seconds 90 --label batched
MIGRATION_BATCH=400ms python3 tests/integration/migration.py --seconds 35 --verify-settled --label recovery
```
