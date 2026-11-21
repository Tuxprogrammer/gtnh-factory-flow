#!/usr/bin/env bash
# A dedicated dev server, for the mod's server side ("Pattern this") with a dev client joined to it. No window.
#
#   tools/dev/server.sh start [timeout_s]   build + launch the server (runServer25), block until it says Done
#   tools/dev/server.sh stop                stop it (only the processes this script started)
#
# Env: PLANNH_SERVER_PORT (25565), PLANNH_GTNH=1 and PLANNH_AE=1 as for mc.sh (the server loads the same mods as the
#      client must). Offline mode, bound to 127.0.0.1, a flat world, peaceful.
# A dev client joins with `--mcJvmArgs=-Dgtnhplanner.dev.server=127.0.0.1:<port>` instead of opening its own world, or
# the harness's `join?to=127.0.0.1:<port>` from a running one. The dev player (Developer) is an operator, so the
# harness's `cmd?c=/give ...` runs server commands. (The pack's RCON fails on every command in a dev run.)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PORT="${PLANNH_SERVER_PORT:-25565}"
RUN="$ROOT/run/server"
STATE="$ROOT/build/dev-server"
LOG="$STATE/server.log"
PIDFILE="$STATE/gradle.pid"

die() { echo "server.sh: $*" >&2; exit 1; }

start() {
    local timeout="${1:-1800}"
    mkdir -p "$RUN" "$STATE"
    # A world per mod set, as mc.sh names its worlds: a world saved with AE2 asks at start before it opens without.
    local world=plannh-dev-server
    if [ "${PLANNH_GTNH:-0}" = 1 ]; then
        world="$world-gtnh"
        [ "${PLANNH_AE:-0}" = 1 ] && world="$world-ae"
    fi
    echo "eula=true" >"$RUN/eula.txt"
    cat >"$RUN/server.properties" <<EOF
server-ip=127.0.0.1
server-port=$PORT
online-mode=false
enable-rcon=false
enable-query=false
level-name=$world
level-type=FLAT
generate-structures=false
spawn-monsters=false
spawn-animals=false
difficulty=0
gamemode=0
spawn-protection=0
view-distance=4
allow-flight=true
snooper-enabled=false
EOF
    # Offline mode names a player by the name's UUID (version 3 of "OfflinePlayer:<name>"), as the server does.
    python3 -c 'import hashlib, uuid
b = bytearray(hashlib.md5(b"OfflinePlayer:Developer").digest()); b[6] = b[6] & 0x0f | 0x30; b[8] = b[8] & 0x3f | 0x80
print("[{\"uuid\": \"%s\", \"name\": \"Developer\", \"level\": 4}]" % uuid.UUID(bytes=bytes(b)))' >"$RUN/ops.json"
    # Angelica is client only and stops a dedicated server's start: -PdevServer leaves it out (dependencies.gradle).
    local run_args=(--no-daemon --console=plain -PdevServer)
    if [ "${PLANNH_GTNH:-0}" = 1 ]; then
        run_args+=(-PgtnhRecipes)
        [ "${PLANNH_AE:-0}" = 1 ] && run_args+=(-PaeDev)
    fi
    run_args+=(downgradeMainClasses runServer25)
    # Nothing the server's mods try to reach leaves the machine: their HTTP(S) goes to a closed local port.
    local p
    for p in http https; do run_args+=("--mcJvmArgs=-D$p.proxyHost=127.0.0.1" "--mcJvmArgs=-D$p.proxyPort=9"); done
    (cd "$ROOT" && exec nohup ../gradlew "${run_args[@]}" >"$LOG" 2>&1 </dev/null) &
    echo $! >"$PIDFILE"
    echo "launching (runServer25), log: $LOG"
    local deadline=$((SECONDS + timeout))
    until grep -q 'Done (' "$LOG" 2>/dev/null; do
        kill -0 "$(cat "$PIDFILE")" 2>/dev/null || { echo "gradle exited; tail of $LOG:" >&2; tail -40 "$LOG" >&2; return 1; }
        [ $SECONDS -ge $deadline ] && { echo "timed out after ${timeout}s" >&2; stop >/dev/null 2>&1; return 1; }
        sleep 5
    done
    echo "done after ${SECONDS}s on 127.0.0.1:$PORT"
}

descendants() {
    local c
    for c in $(ps -o pid= --ppid "$1"); do
        descendants "$c"
        echo "$c"
    done
}

stop() {
    local pid
    pid="$(cat "$PIDFILE" 2>/dev/null)" || { echo "not started here"; return 0; }
    # The server's JVM is below gradle (a forked build JVM may sit between): what descends from ours, then gradle.
    # TERM first: the server saves its world on the way out.
    local kids
    kids="$(descendants "$pid")"
    for c in $kids; do kill "$c" 2>/dev/null; done
    for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    for c in $kids "$pid"; do kill -9 "$c" 2>/dev/null; done
    rm -f "$PIDFILE"
    echo stopped
}

case "${1:-}" in
    start) start "${2:-1800}" ;;
    stop) stop ;;
    *) die "usage: server.sh start [timeout_s] | stop" ;;
esac
