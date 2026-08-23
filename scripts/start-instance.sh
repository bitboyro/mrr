#!/usr/bin/env bash
# Start an insulated mrr + Cloudflare quick tunnel in a new Ghostty window / tmux session.
#
# The running JVM loads a *copy* of the jar and its own SQLite under ~/.mrr (by default),
# so `mvn package`, branch switches, and a second instance on :8130 do not touch it.
#
# Usage:
#   ./scripts/start-instance.sh            # start or attach
#   ./scripts/start-instance.sh --refresh  # recopy the jar; restart the session if it is up
#   ./scripts/start-instance.sh --restart  # kill the session and start clean
#   ./scripts/start-instance.sh --stop     # kill the session
#
# Env:
#   MRR_INSTANCE_HOME   default: $HOME/.mrr
#   MRR_INSTANCE_PORT   default: 8131  (leave 8130 for a repo-local run)
#   MRR_TMUX_SESSION    default: mrr
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOME_DIR="${MRR_INSTANCE_HOME:-$HOME/.mrr}"
PORT="${MRR_INSTANCE_PORT:-8131}"
SESSION="${MRR_TMUX_SESSION:-mrr}"
SCRIPT="$ROOT/scripts/start-instance.sh"
JAR_SRC="$ROOT/target/mrr.jar"
JAR="$HOME_DIR/mrr.jar"
DATA="$HOME_DIR/data"
LOGS="$HOME_DIR/logs"
REFRESH=0
RESTART=0

usage() {
  sed -n '2,18p' "$0" | sed 's/^# \?//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --refresh) REFRESH=1; shift ;;
    --restart) RESTART=1; shift ;;
    --stop)
      if tmux has-session -t "$SESSION" 2>/dev/null; then
        tmux kill-session -t "$SESSION"
        echo "stopped tmux session '$SESSION'"
      else
        echo "no tmux session '$SESSION'"
      fi
      exit 0
      ;;
    --attach)
      exec tmux attach-session -t "$SESSION"
      ;;
    _mrr|_tunnel|_info)
      action="$1"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing '$1' on PATH" >&2
    exit 1
  }
}

prepare() {
  mkdir -p "$DATA" "$LOGS" "$DATA/memory"
  if [[ ! -f "$JAR_SRC" ]]; then
    echo "no jar at $JAR_SRC — build one first:" >&2
    echo "  mvn -DskipTests package" >&2
    exit 1
  fi
  if [[ ! -f "$JAR" || "$REFRESH" -eq 1 ]]; then
    cp -f "$JAR_SRC" "$JAR"
    echo "copied $(basename "$JAR_SRC") → $JAR"
  fi
  if [[ ! -f "$DATA/mrr.db" && -f "$ROOT/data/mrr.db" ]]; then
    command -v sqlite3 >/dev/null 2>&1 || {
      echo "missing 'sqlite3' on PATH — needed to copy a live database without dropping WAL" >&2
      exit 1
    }
    sqlite3 "$ROOT/data/mrr.db" ".backup '$DATA/mrr.db'"
    echo "seeded instance db from $ROOT/data/mrr.db"
  fi
  if [[ ! -f "$DATA/root-token.txt" && -f "$ROOT/data/root-token.txt" ]]; then
    cp -f "$ROOT/data/root-token.txt" "$DATA/root-token.txt"
    chmod 600 "$DATA/root-token.txt" 2>/dev/null || true
  fi
}

open_ghostty() {
  local app="/Applications/Ghostty.app"
  if [[ -d "$app" ]]; then
    open -na "$app" --args --working-directory="$HOME_DIR" -e "$SCRIPT" --attach
    return
  fi
  echo "Ghostty.app not found — attach from this terminal:"
  echo "  tmux attach -t $SESSION"
}

# ── panes (called from tmux) ──────────────────────────────────────────────────

run_mrr() {
  echo "waiting for tunnel hostname before boot (so webhook URLs print correctly)…"
  local n=0
  while [[ $n -lt 45 && ! -f "$LOGS/base-url.txt" ]]; do
    sleep 1
    n=$((n + 1))
  done
  export MRR_DB_PATH="$DATA/mrr.db"
  export MRR_ROOT_TOKEN_FILE="$DATA/root-token.txt"
  export MRR_MEMORY_ROOT="$DATA/memory"
  export SERVER_PORT="$PORT"
  if [[ -f "$LOGS/base-url.txt" ]]; then
    export MRR_BASE_URL
    MRR_BASE_URL="$(tr -d '[:space:]' < "$LOGS/base-url.txt")"
    echo "MRR_BASE_URL=$MRR_BASE_URL"
  else
    echo "tunnel hostname not ready — webhook URLs will show localhost until you restart" >&2
  fi
  echo "mrr  jar=$JAR  port=$PORT  db=$MRR_DB_PATH"
  exec java -jar "$JAR"
}

run_tunnel() {
  : > "$LOGS/cloudflared.log"
  rm -f "$LOGS/base-url.txt"
  cloudflared tunnel --url "http://127.0.0.1:$PORT" 2>&1 | tee "$LOGS/cloudflared.log" | while IFS= read -r line; do
    echo "$line"
    if [[ "$line" =~ https://[a-z0-9-]+\.trycloudflare\.com ]]; then
      url="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' <<<"$line" | head -1)"
      printf '%s\n' "$url" > "$LOGS/base-url.txt"
    fi
  done
}

run_info() {
  echo "waiting for tunnel hostname and http://127.0.0.1:$PORT/health …"
  local url=""
  local i=0
  while [[ $i -lt 60 ]]; do
    if [[ -f "$LOGS/base-url.txt" ]]; then
      url="$(tr -d '[:space:]' < "$LOGS/base-url.txt")"
    fi
    if [[ -n "$url" ]] && curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
      break
    fi
    sleep 1
    i=$((i + 1))
  done

  echo
  echo "────────────────────────────────────────────────────────────"
  echo "  mrr instance  (insulated from the git checkout)"
  echo "────────────────────────────────────────────────────────────"
  echo "  local     http://127.0.0.1:$PORT"
  echo "  public    ${url:-"(tunnel hostname not parsed — see $LOGS/cloudflared.log)"}"
  echo "  health    http://127.0.0.1:$PORT/health"
  echo "  ui        ${url:-http://127.0.0.1:$PORT}/"
  echo "  swagger   ${url:-http://127.0.0.1:$PORT}/swagger-ui"
  echo "  jar       $JAR"
  echo "  data      $DATA"
  echo "  port      $PORT   (repo-local default is 8130)"
  echo "  tmux      $SESSION   —  stop:  $SCRIPT --stop"
  if [[ -f "$DATA/root-token.txt" ]]; then
    echo
    echo "  root token (paste into the UI):"
    echo "    $(tr -d '[:space:]' < "$DATA/root-token.txt")"
  else
    echo
    echo "  root token: not written yet — check the mrr pane banner"
  fi
  echo
  echo "  GitLab webhook  (Settings → Webhooks)"
  echo "    URL shape:  ${url:-https://<tunnel>}/webhooks/gitlab/<project-id>"
  echo "    events:     Merge request events, and Comments if replies should land"
  echo "    secret:     the one shown once at project create — not stored in this pane"
  if command -v sqlite3 >/dev/null 2>&1 && [[ -f "$DATA/mrr.db" ]]; then
    echo
    sqlite3 -separator '  ' "$DATA/mrr.db" \
      "SELECT '    ' || name || ':  ' || '${url:-https://<tunnel>}' || '/webhooks/gitlab/' || id FROM projects;" \
      2>/dev/null || true
  fi
  echo
  echo "  A new quick-tunnel hostname must be pasted over the old webhook URL."
  echo "  Two enabled hooks double-fire reviews."
  echo "────────────────────────────────────────────────────────────"
  echo
  echo "this pane stays open; Ctrl-b d detaches.  $SCRIPT --stop to tear down."
  exec "${SHELL:-/bin/zsh}"
}

if [[ "${action:-}" == "_mrr" ]]; then run_mrr; fi
if [[ "${action:-}" == "_tunnel" ]]; then run_tunnel; exit; fi
if [[ "${action:-}" == "_info" ]]; then run_info; fi

need tmux
need java
need cloudflared
need curl

# Recopy before the "already running" attach path. A live JVM keeps the old
# mapped jar, so --refresh also tears the session down and boots again.
if [[ "$REFRESH" -eq 1 ]]; then
  prepare
fi

if { [[ "$RESTART" -eq 1 ]] || [[ "$REFRESH" -eq 1 ]]; } \
    && tmux has-session -t "$SESSION" 2>/dev/null; then
  tmux kill-session -t "$SESSION"
  if [[ "$REFRESH" -eq 1 ]]; then
    echo "restarting tmux session '$SESSION' so the refreshed jar is loaded"
  fi
fi

if tmux has-session -t "$SESSION" 2>/dev/null; then
  echo "tmux session '$SESSION' already running — attaching"
  open_ghostty
  exit 0
fi

if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "port $PORT is already in use" >&2
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >&2 || true
  exit 1
fi

prepare

# Tunnel first so the hostname exists before Java boots (MRR_BASE_URL).
tmux new-session -d -s "$SESSION" -n run -c "$HOME_DIR" "$SCRIPT" _tunnel
tmux split-window -h -t "$SESSION":0 -c "$HOME_DIR" "$SCRIPT" _mrr
tmux split-window -v -t "$SESSION":0.0 -c "$HOME_DIR" "$SCRIPT" _info
tmux select-layout -t "$SESSION":0 tiled
tmux select-pane -t "$SESSION":0.2

echo "started tmux session '$SESSION'  (jar copy in $HOME_DIR, port $PORT)"
open_ghostty
