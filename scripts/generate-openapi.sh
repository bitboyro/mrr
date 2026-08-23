#!/usr/bin/env bash
#
# Boots the packaged app on a port of its own, exports the spec off it, and stops it again —
# on every path out, including a failed export. Called by `mvn verify`; run it by hand with
# a jar already built.
#
#   OPENAPI_PORT=8137 ./scripts/generate-openapi.sh
#
set -euo pipefail

PORT="${OPENAPI_PORT:-8137}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${OPENAPI_JAR:-$ROOT/target/mrr.jar}"
WORK="$ROOT/target"
LOG="$WORK/openapi-server.log"

if [[ ! -f "$JAR" ]]; then
  echo "error: no jar at $JAR — package first (mvn -DskipTests package)" >&2
  exit 1
fi

# Everything this instance writes stays under target/: the build must never touch the
# database, root token, or memory tree of an instance anyone is actually using.
java -Dserver.port="$PORT" \
     -DMRR_DB_PATH="$WORK/openapi-mrr.db" \
     -DMRR_ROOT_TOKEN_FILE="$WORK/openapi-root-token.txt" \
     -DMRR_MEMORY_ROOT="$WORK/openapi-memory" \
     -jar "$JAR" > "$LOG" 2>&1 &
pid=$!

# The trap is the point of this script existing. Maven aborts the lifecycle as soon as a
# phase fails, so a spring-boot:stop bound to post-integration-test never runs on the paths
# where it matters — the server outlives the build and holds the port against the next one.
cleanup() {
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}
trap cleanup EXIT

ready=
for _ in $(seq 1 60); do
  if curl -sf --max-time 2 "http://localhost:$PORT/health" >/dev/null 2>&1; then
    ready=1
    break
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "error: the app exited during startup — last lines of $LOG:" >&2
    tail -20 "$LOG" >&2
    exit 1
  fi
  sleep 1
done

if [[ -z "$ready" ]]; then
  echo "error: no health response on port $PORT within 60s — see $LOG" >&2
  exit 1
fi

MRR_URL="http://localhost:$PORT" "$ROOT/scripts/export-openapi.sh"
