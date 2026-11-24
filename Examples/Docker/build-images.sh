#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
    printf '%s\n' 'Usage: build-images.sh' \
        'Build and load the Minecraft Node and Valkey images into local Docker.' \
        'Set BUILDER to select a Buildx builder or MINECRAFT_RUNTIME_INPUTS to select an input directory.'
    exit 0
fi
if (( $# != 0 )); then
    printf '%s\n' 'Usage: build-images.sh' >&2
    exit 2
fi

docker buildx version >/dev/null

production_dockerfile=AtlasNet/dev/docker/Production.DockerFile
if [[ ! -f "$production_dockerfile" ]]; then
    git submodule update --init --recursive AtlasNet
fi

# The currently pinned submodule predates the production stages used by Bake.
if ! grep -q ' AS atlasnet-node-valkeymodule$' "$production_dockerfile"; then
    production_revision=4607c20410ac3bcf8925f8db19c93e2cdc262954
    previous_revision=eb75bb193abc37c5d5422ced3603896168014300
    if [[ "$(git -C AtlasNet rev-parse HEAD)" != "$previous_revision" ||
          -n "$(git -C AtlasNet status --porcelain)" ]]; then
        printf 'AtlasNet must contain the production Docker stages from %s. Check your submodule checkout.\n' \
            "$production_revision" >&2
        exit 1
    fi
    printf 'Checking out AtlasNet production image revision %s.\n' "$production_revision"
    if ! git -C AtlasNet cat-file -e "$production_revision^{commit}" 2>/dev/null; then
        git -C AtlasNet fetch origin "$production_revision"
    fi
    git -C AtlasNet switch --detach "$production_revision"
fi

export MINECRAFT_RUNTIME_INPUTS="${MINECRAFT_RUNTIME_INPUTS:-.tools/docker-minecraft-inputs}"
inputs="$MINECRAFT_RUNTIME_INPUTS"
loom_cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/fabric-loom"
java_gi_version=1.0.0-RC3
glib_jar="glib-$java_gi_version.jar"
jspecify_jar=jspecify-1.0.1.jar

mapped_minecraft_jar() {
    local jar version_dir
    for jar in "$loom_cache"/minecraftMaven/net/minecraft/minecraft-common/1.21.1-*/minecraft-common-1.21.1-*.jar; do
        version_dir="${jar%/*}"
        if [[ -f "$jar" && "${jar##*/}" == "minecraft-common-${version_dir##*/}.jar" ]]; then
            printf '%s\n' "$jar"
            return
        fi
    done
    return 1
}

download() {
    local url="$1" destination="$2" checksum="$3"
    curl --fail --silent --show-error --location --retry 3 --output "$destination" "$url"
    if ! printf '%s  %s\n' "$checksum" "$destination" | sha256sum --check --status; then
        printf 'Checksum verification failed for %s.\n' "$url" >&2
        return 1
    fi
}

prepare_inputs() (
    mkdir -p "$inputs"
    temporary="$(mktemp -d "$inputs/.prepare.XXXXXX")"
    trap 'rm -rf -- "$temporary"' EXIT

    if [[ ! -x "$inputs/java-gi/bin/java-gi" ]]; then
        if [[ -e "$inputs/java-gi" ]]; then
            printf 'Expected an executable generator at %s/java-gi/bin/java-gi.\n' "$inputs" >&2
            exit 1
        fi
        printf 'Downloading Java-GI %s.\n' "$java_gi_version"
        download "https://codeberg.org/java-gi/java-gi/releases/download/$java_gi_version/java-gi-$java_gi_version.zip" \
            "$temporary/java-gi.zip" ba7fb81a934279a2b68499fd1589a9ce1d2c2190d1bd15cdf729bd974780dec2
        unzip -q "$temporary/java-gi.zip" -d "$temporary"
        mv "$temporary/java-gi-$java_gi_version" "$inputs/java-gi"
    fi
    if [[ ! -s "$inputs/$glib_jar" ]]; then
        download "https://repo.maven.apache.org/maven2/org/java-gi/glib/$java_gi_version/$glib_jar" \
            "$temporary/$glib_jar" cf28f1b88721bc1090c29b8324a488b0696cf593b24603b49f2790f10ad23ca5
        mv "$temporary/$glib_jar" "$inputs/$glib_jar"
    fi
    if [[ ! -s "$inputs/$jspecify_jar" ]]; then
        cp "$inputs/java-gi/lib/$jspecify_jar" "$temporary/$jspecify_jar"
        mv "$temporary/$jspecify_jar" "$inputs/$jspecify_jar"
    fi

    if [[ -s "$inputs/minecraft-common.jar" && -d "$inputs/libraries" ]]; then
        return
    fi

    server_bundle="$loom_cache/1.21.1/minecraft-server.jar"
    mapped_jar="$(mapped_minecraft_jar || true)"
    if [[ ( ! -s "$inputs/minecraft-common.jar" && -z "$mapped_jar" ) ||
          ( ! -d "$inputs/libraries" && ! -s "$server_bundle" ) ]]; then
        printf '%s\n' 'Preparing Minecraft 1.21.1 with Fabric Loom. This requires a host JDK 21 or newer.'
        ./legacy/gradlew --project-dir legacy --console=plain :fabric:help
        mapped_jar="$(mapped_minecraft_jar || true)"
    fi
    if [[ ! -s "$inputs/minecraft-common.jar" ]]; then
        if [[ -z "$mapped_jar" ]]; then
            printf 'Fabric Loom did not produce a Mojang-mapped Minecraft JAR under %s.\n' "$loom_cache" >&2
            exit 1
        fi
        cp "$mapped_jar" "$temporary/minecraft-common.jar"
        mv "$temporary/minecraft-common.jar" "$inputs/minecraft-common.jar"
    fi
    if [[ ! -d "$inputs/libraries" ]]; then
        unzip -q "$server_bundle" 'META-INF/libraries/*.jar' -d "$temporary"
        mv "$temporary/META-INF/libraries" "$inputs/libraries"
    fi
)

prepare_inputs

builder="${BUILDER:-atlasnet-builder}"
if ! docker buildx inspect "$builder" >/dev/null 2>&1; then
    docker buildx create --name "$builder" --driver docker-container
fi

exec docker buildx bake --builder "$builder" -f Examples/Docker/docker-bake.hcl --load
