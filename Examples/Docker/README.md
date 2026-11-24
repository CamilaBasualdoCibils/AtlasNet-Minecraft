# AtlasNet Minecraft Swarm example

This stack runs four services.

| Service | Replicas | Role |
| --- | ---: | --- |
| `valkey` | 1 | Valkey on a Swarm manager, with AOF persisted in `valkey-data` |
| `postgres` | 1 | PostgreSQL 17 on a Swarm manager, with Minecraft checkpoints and terrain persisted in `postgres-data` |
| `manager` | 1 | AtlasNet Database and Control Panel on a Swarm manager |
| `minecraft` | 2 | AtlasNet Database, ControllerEligible, Shard, and ClientIngress. Each task can run one Minecraft shard worker. |

The images use the same [production Dockerfile](../../AtlasNet/dev/docker/Production.DockerFile)
as [BuildProductionImages.sh](../../AtlasNet/dev/BuildProductionImages.sh).
Buildx extends its node runtime with the four JVM-hosted Minecraft modules,
the Java binding and JDK 25, and the X-axis split heuristic. The Valkey service
runs the server from the production Valkey image. The standalone manager
provides the AtlasNet registry endpoint, so the stack uses the image's
standard Valkey entrypoint without starting its optional embedded Node.

## Build the images

Run from the Minecraft repository root with Docker Buildx available.

```sh
./Examples/Docker/build-images.sh
```

The script initializes the AtlasNet submodule, downloads Java-GI if needed,
and uses Fabric Loom to prepare Minecraft 1.21.1 if it is missing from the
local Gradle cache. A cold start needs network access, `curl`, `unzip`,
`sha256sum`, and a host JDK 21 or newer for Loom. The script advances a clean
checkout of the older pinned AtlasNet submodule to the tested production image
revision. Set `BUILDER` to use a different Buildx builder. The default build
loads `atlasnet-minecraft-node:latest` and `atlasnet-node-valkeymodule:latest`
into the local Docker image store.

| Path under `.tools/docker-minecraft-inputs/` | Contents |
| --- | --- |
| `java-gi/` | Extracted Java-GI 1.0.0-RC3 generator distribution, including executable `bin/java-gi` |
| `glib-1.0.0-RC3.jar` | The matching Java-GI GLib runtime JAR used by this repository |
| `jspecify-1.0.1.jar` | The Java-GI jspecify dependency |
| `minecraft-common.jar` | The 1.21.1 common/server JAR in official Mojang mappings, with vanilla resources |
| `libraries/` | Contents of `META-INF/libraries/` extracted from the 1.21.1 server bundle |

You can export `MINECRAFT_RUNTIME_INPUTS` to point at another directory with
this layout. Keep the resulting Minecraft image private because it contains
the Minecraft runtime JARs.

For a multi-host Swarm, set `MINECRAFT_NODE_IMAGE` and
`ATLASNET_VALKEY_IMAGE` to private registry references before building and
when deploying. With the inputs prepared, run
`docker buildx bake -f Examples/Docker/docker-bake.hcl --push`. For a mixed
amd64/arm64 Swarm, add `--set '*.platform=linux/amd64,linux/arm64'` and use
a builder that supports both architectures.

## Run the stack

Deploy from a Swarm manager. Set `MINECRAFT_EULA=true` only after you accept
the [Minecraft EULA](https://www.minecraft.net/eula). This creates the
required acceptance file in each worker's local world directory.

```sh
export MINECRAFT_EULA=true
docker stack config -c Examples/Docker/stack.yml
docker stack deploy --resolve-image never -c Examples/Docker/stack.yml atlasnet-minecraft
docker stack services atlasnet-minecraft
```

For registry deployment, use `--with-registry-auth` in place of
`--resolve-image never`. Allow time for Minecraft world generation and
shard registration. The Control Panel
listens on TCP `8080`, and Minecraft Java Edition 1.21.1 connects on TCP
`25565`, both through Swarm's routing mesh. Workers bootstrap against the
manager task directly over overlay UDP. Valkey and PostgreSQL are reachable
within the overlay. Each Minecraft task forwards its local JDBC port to
`postgres:5432` because the current Java host connects to localhost.

Swarm configs are immutable. When editing a YAML file for a previous
deployment, give its config a new key and update the corresponding
`source` in `stack.yml`. AtlasNet also persists the cluster policy in
Valkey. Use a fresh Valkey registry when initializing a different cluster
policy.

This example exercises the repository's current Java TCP endpoint and online
Minecraft login. The endpoint hands admitted sessions to AtlasNet for
placement and gameplay routing. The production image also contains the
SteamNetSock module.

The local smoke test verified two Minecraft tasks, one ready
`AtlasNetShardWorker` per task, and a vanilla 1.21.1 client joining through
`127.0.0.1:25565`.

Valkey and PostgreSQL use manager-local named volumes. On a Swarm with
multiple managers, pin both stateful services to the intended storage host
before deploying if the volumes need to survive task rescheduling. Minecraft
worker directories are task-local. The default PostgreSQL password is for the
example. Set `ATLASNET_POSTGRES_PASSWORD` before the first deployment to use
your own password. Changing the environment after initialization also
requires changing the stored role password. The example publishes the
Control Panel and game endpoint to the Swarm, so run it on a trusted network.
