#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
stack_id="$(printf '%s' "$project_dir" | sha256sum | cut -c1-12)"
container="atlasnet-minecraft-postgres-$stack_id"
data_dir="$project_dir/build/postgres"
mount_type=bind
mount_source="$data_dir"
if [[ -n "${ATLASNET_POSTGRES_VOLUME:-}" ]]; then
    mount_type=volume
    mount_source="$ATLASNET_POSTGRES_VOLUME"
fi
port="${ATLASNET_POSTGRES_PORT:-45432}"
export POSTGRES_DB="${ATLASNET_POSTGRES_DATABASE:-minecraft}"
export POSTGRES_USER="${ATLASNET_POSTGRES_USER:-minecraft}"
export POSTGRES_PASSWORD="${ATLASNET_POSTGRES_PASSWORD:-minecraft-dev}"

case "${1:-start}" in
    stop)
        docker info --format '{{.ServerVersion}}' >/dev/null
        if docker container inspect "$container" >/dev/null 2>&1; then
            docker stop --time 10 "$container" >/dev/null
            docker rm "$container" >/dev/null
        fi
        exit 0
        ;;
    start) ;;
    *) printf 'Usage: %s [start|stop]\n' "$0" >&2; exit 2 ;;
esac

docker info --format '{{.ServerVersion}}' >/dev/null
if [[ ! "$port" =~ ^[1-9][0-9]{0,4}$ ]] || (( port > 65535 )); then
    printf 'Invalid PostgreSQL port: %s\n' "$port" >&2
    exit 2
fi

if docker container inspect "$container" >/dev/null 2>&1; then
    configured_port="$(docker inspect --format '{{with index .HostConfig.PortBindings "5432/tcp"}}{{(index . 0).HostPort}}{{end}}' "$container")"
    if [[ "$configured_port" != "$port" ]]; then
        printf 'Existing PostgreSQL container uses a different port. Run %s stop before changing ports.\n' "$0" >&2
        exit 1
    fi
    configured_mount="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Type}}:{{if eq .Type "volume"}}{{.Name}}{{else}}{{.Source}}{{end}}{{end}}{{end}}' "$container")"
    if [[ "$configured_mount" != "$mount_type:$mount_source" ]]; then
        printf 'Existing PostgreSQL container uses %s. Stop and migrate its data before changing storage.\n' "$configured_mount" >&2
        exit 1
    fi
    docker start "$container" >/dev/null
else
    if [[ "$mount_type" == bind ]]; then
        mkdir -p "$data_dir"
    fi
    docker run --detach --name "$container" \
        --publish "127.0.0.1:$port:5432" \
        --mount "type=$mount_type,source=$mount_source,target=/var/lib/postgresql/data" \
        --env POSTGRES_DB --env POSTGRES_USER --env POSTGRES_PASSWORD \
        postgres:17 >/dev/null
fi

deadline=$((SECONDS + 45))
while (( SECONDS < deadline )); do
    if docker exec --env PGUSER="$POSTGRES_USER" --env PGDATABASE="$POSTGRES_DB" \
        --env PGCONNECT_TIMEOUT=2 --env PGPASSWORD="$POSTGRES_PASSWORD" \
        "$container" psql -h 127.0.0.1 -Atc 'SELECT 1' >/dev/null 2>&1; then
        printf 'Minecraft PostgreSQL ready on 127.0.0.1:%s, database %s, user %s\n' "$port" "$POSTGRES_DB" "$POSTGRES_USER"
        exit 0
    fi
    [[ "$(docker inspect --format '{{.State.Running}}' "$container")" == true ]] || break
    sleep 1
done

printf 'PostgreSQL failed its connection check. Recent container logs:\n' >&2
docker logs --tail 30 "$container" >&2 || true
exit 1
