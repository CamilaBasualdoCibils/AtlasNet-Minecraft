#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

select_jdk() {
    local candidate
    for candidate in "${JAVA_HOME:-}" \
        "$root"/.tools/jdk-25* "$root"/legacy/.tools/jdk-25* /usr/lib/jvm/*; do
        if [[ -n "$candidate" && -x "$candidate/bin/javac" ]] &&
            grep -Eq '^JAVA_VERSION="25([.+"]|$)' "$candidate/release"; then
            export JAVA_HOME="$candidate"
            export PATH="$JAVA_HOME/bin:$PATH"
            return
        fi
    done
    printf 'Minecraft modules require JDK 25. Set JAVA_HOME to your JDK 25 installation.\n' >&2
    exit 1
}

gradle_build() {
    local loom path bundle
    for loom in "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/fabric-loom" \
        "$root/legacy/.tools/gradle-home/caches/fabric-loom"; do
        if [[ -z "${ORG_GRADLE_PROJECT_minecraftAdmissionJar:-}" ]]; then
            for path in "$loom"/minecraftMaven/net/minecraft/minecraft-common/1.21.1-*/minecraft-common-1.21.1-*.jar; do
                [[ -f "$path" ]] || continue
                local version_dir="${path%/*}"
                [[ "${path##*/}" == "minecraft-common-${version_dir##*/}.jar" ]] || continue
                export ORG_GRADLE_PROJECT_minecraftAdmissionJar="$path"
                break
            done
        fi
        bundle="$loom/1.21.1/minecraft-server.jar"
        if [[ -z "${ORG_GRADLE_PROJECT_minecraftAdmissionLibraries:-}" && -f "$bundle" ]]; then
            local extracted="$root/build/minecraft-1.21.1-runtime"
            if [[ ! -f "$extracted/.extracted" || "$bundle" -nt "$extracted/.extracted" ]]; then
                mkdir -p "$extracted"
                unzip -oq "$bundle" 'META-INF/libraries/*.jar' -d "$extracted"
                touch "$extracted/.extracted"
            fi
            export ORG_GRADLE_PROJECT_minecraftAdmissionLibraries="$extracted/META-INF/libraries"
        fi
    done
    exec "$root/gradlew" --console=plain "$@"
}

stack_build() {
    local world="${ATLASNET_MINECRAFT_WORLD_DIR:-minecraft-shard}"
    [[ "$world" = /* ]] || world="$root/build/$world"
    if [[ ! -e "$world/eula.txt" ]] &&
        grep -Eq '^eula=true\s*$' "$root/legacy/fabric/run/server/eula.txt" 2>/dev/null; then
        mkdir -p "$world"
        cp -n "$root/legacy/fabric/run/server/eula.txt" "$world/eula.txt"
    fi
    if ! grep -Eq '^eula=true\s*$' "$world/eula.txt" 2>/dev/null; then
        printf 'Prepare %s/eula.txt with your Minecraft EULA acceptance before launching.\n' "$world" >&2
        exit 1
    fi

    local jobs="${CMAKE_BUILD_PARALLEL_LEVEL:-4}"
    if [[ -z "${VCPKG_ROOT:-}" ]]; then
        printf 'Set VCPKG_ROOT to your vcpkg installation before launching.\n' >&2
        exit 1
    fi
    cmake --preset minecraft
    GI_SCANNER_DISABLE_CACHE=1 cmake --build "$root/build" --parallel "$jobs" \
        --target atlasnet-minecraft-stack
    exec "$root/scripts/minecraft-postgres.sh"
}

select_jdk
case "${1:-}" in
    gradle) shift; gradle_build "$@" ;;
    stack) stack_build ;;
    *) printf 'Usage: %s {gradle TASK…|stack}\n' "$0" >&2; exit 2 ;;
esac
