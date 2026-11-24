#!/bin/sh
set -eu

if [ "${MINECRAFT_EULA:-false}" != true ]; then
    echo 'Set MINECRAFT_EULA=true to record your acceptance of the Minecraft EULA.' >&2
    exit 1
fi

mkdir -p "$ATLASNET_MINECRAFT_WORLD_DIR"
printf 'eula=true\n' > "$ATLASNET_MINECRAFT_WORLD_DIR/eula.txt"

# The current Java host sets a JDBC port and uses localhost.
socat "TCP-LISTEN:${ATLASNET_POSTGRES_PORT},bind=127.0.0.1,reuseaddr,fork" TCP:postgres:5432 &

exec /atlasnet/AtlasNetNode
