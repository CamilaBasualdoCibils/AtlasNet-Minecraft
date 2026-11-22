#!/usr/bin/env bash
# HotSpot handles these signals internally during startup and execution.
exec /usr/bin/gdb \
    -iex 'handle SIGSEGV nostop noprint pass' \
    -iex 'handle SIGBUS nostop noprint pass' \
    "$@"
