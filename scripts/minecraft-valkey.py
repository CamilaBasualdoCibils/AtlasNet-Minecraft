#!/usr/bin/env python3
"""Manage the local Minecraft Valkey instance independently of AtlasNet."""
import argparse
import fcntl
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import uuid


ROOT = Path(__file__).resolve().parents[1]


def read_reply(stream):
    line = stream.readline(65536)
    if not line:
        raise EOFError("Valkey closed the connection")
    if not line.endswith(b"\r\n"):
        raise RuntimeError("Invalid Valkey response")
    marker, value = line[:1], line[1:-2]
    if marker == b"+":
        return value
    if marker == b"-":
        raise RuntimeError(value.decode(errors="replace"))
    if marker == b"$":
        size = int(value)
        if size == -1:
            return None
        if not 0 <= size <= 65536:
            raise RuntimeError("Unexpected Valkey bulk response size")
        result = stream.read(size)
        if len(result) != size or stream.read(2) != b"\r\n":
            raise RuntimeError("Truncated Valkey bulk response")
        return result
    if marker == b"*":
        count = int(value)
        if not 0 <= count <= 32:
            raise RuntimeError("Unexpected Valkey response array size")
        return [read_reply(stream) for _ in range(count)]
    raise RuntimeError("Unexpected Valkey reply")


def command(port, *arguments):
    parts = [f"*{len(arguments)}\r\n".encode()]
    for argument in arguments:
        data = argument.encode()
        parts.extend((f"${len(data)}\r\n".encode(), data, b"\r\n"))
    with socket.create_connection(("127.0.0.1", port), timeout=1) as client:
        client.sendall(b"".join(parts))
        with client.makefile("rb") as stream:
            return read_reply(stream)


def compatible_instance(port, directory):
    try:
        info = command(port, "INFO", "server")
    except ConnectionRefusedError:
        return False
    try:
        process = next(line.split(b":", 1)[1] for line in info.splitlines()
                       if line.startswith(b"process_id:"))
        config = command(port, "CONFIG", "GET", "dir", "appendonly", "dbfilename")
        settings = dict(zip(config[::2], config[1::2]))
        pid = (directory / "valkey.pid").read_bytes().strip()
        if (process == pid and Path(os.fsdecode(settings[b"dir"])).resolve() == directory
                and settings[b"appendonly"] == b"yes"
                and settings[b"dbfilename"] == b"minecraft.rdb"):
            return True
    except (AttributeError, KeyError, OSError, StopIteration, TypeError):
        pass
    raise RuntimeError(f"Port {port} is occupied by a service outside the Minecraft Valkey instance")


def start(port, directory):
    if compatible_instance(port, directory):
        print(f"Minecraft Valkey already running at 127.0.0.1:{port}")
        return
    executable = os.environ.get("MINECRAFT_VALKEY_SERVER") or ROOT / "build/_deps/valkey-src/src/valkey-server"
    if not os.access(executable, os.X_OK):
        raise RuntimeError("Build the BuildServer target first, or set MINECRAFT_VALKEY_SERVER to valkey-server")
    subprocess.run([str(executable), "--bind", "127.0.0.1", "--port", str(port),
                    "--dir", str(directory), "--dbfilename", "minecraft.rdb",
                    "--appendonly", "yes", "--appendfsync", "everysec",
                    "--daemonize", "yes", "--pidfile", str(directory / "valkey.pid"),
                    "--logfile", str(directory / "valkey.log")], check=True)
    for _ in range(50):
        if compatible_instance(port, directory):
            print(f"Minecraft Valkey ready at 127.0.0.1:{port}. Data directory {directory}")
            return
        time.sleep(0.1)
    raise RuntimeError(f"Minecraft Valkey did not start. See {directory / 'valkey.log'}")


def stop(port, directory):
    if not compatible_instance(port, directory):
        print("Minecraft Valkey is already stopped")
        return
    try:
        command(port, "SHUTDOWN", "SAVE")
    except EOFError:
        pass
    for _ in range(50):
        if not compatible_instance(port, directory):
            print("Minecraft Valkey saved and stopped")
            return
        time.sleep(0.1)
    raise RuntimeError("Minecraft Valkey did not stop")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("start", "stop"))
    parser.add_argument("--stack-session", action="store_true")
    args = parser.parse_args()
    if args.stack_session and args.action != "start":
        parser.error("--stack-session requires start")
    normal = os.environ.get("MINECRAFT_TERRAIN_PRESET", "normal") == "normal"
    port = int(os.environ.get("MINECRAFT_VALKEY_PORT", "46382" if normal else "46381"))
    if not 1 <= port <= 65535:
        raise ValueError("MINECRAFT_VALKEY_PORT must be between 1 and 65535")
    default_directory = ROOT / "run" / ("minecraft-valkey-normal" if normal else "minecraft-valkey")
    directory = Path(os.environ.get("MINECRAFT_VALKEY_DIR", default_directory)).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / "lifecycle.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        (start if args.action == "start" else stop)(port, directory)
        if args.stack_session:
            session = ROOT / "build/minecraft-terrain-session"
            pending = session.with_name(f"{session.name}.{os.getpid()}.tmp")
            pending.write_text(f"{uuid.uuid4()}\n")
            pending.replace(session)


if __name__ == "__main__":
    try:
        main()
    except (OSError, EOFError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Minecraft Valkey: {error}", file=sys.stderr)
        sys.exit(1)
