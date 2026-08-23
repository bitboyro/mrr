#!/usr/bin/env bash
#
# Regenerates docs/openapi.json and docs/mcp-tools.json from a running mrr.
#
# Both are generated rather than hand-written, so they cannot drift from the annotations.
# `mvn verify` already regenerates the openapi half against an instance it boots itself;
# this script is the manual path, and the only way to refresh mcp-tools.json, which needs
# a token and an MCP session. Commit the result alongside the code change.
#
#   ./scripts/export-contracts.sh                    # against localhost:8130
#   MRR_URL=https://mrr.example.com MRR_TOKEN=mrr_… ./scripts/export-contracts.sh
#
set -euo pipefail

MRR_URL="${MRR_URL:-http://localhost:8130}"
MRR_TOKEN="${MRR_TOKEN:-}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/docs"
mkdir -p "$OUT"

if ! curl -sf --max-time 10 "$MRR_URL/health" >/dev/null; then
  echo "error: no mrr at $MRR_URL — start it first (java -jar target/mrr.jar)" >&2
  exit 1
fi

# ── OpenAPI ─────────────────────────────────────────────────────────────────────────
MRR_URL="$MRR_URL" "$(dirname "$0")/export-openapi.sh"

# ── MCP tools ───────────────────────────────────────────────────────────────────────
# Read from a live tools/list rather than parsed out of the source, so the exported
# contract is exactly what a client will be offered.
if [[ -z "$MRR_TOKEN" ]]; then
  echo "mcp-tools -> skipped (set MRR_TOKEN to a valid mrr API token)" >&2
  exit 0
fi

echo "mcp-tools -> docs/mcp-tools.json"
hdr=(-H "X-API-Key: $MRR_TOKEN" -H 'Content-Type: application/json'
     -H 'Accept: application/json, text/event-stream')

session=$(curl -sf -D- -o /dev/null "${hdr[@]}" "$MRR_URL/mcp" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"export-contracts","version":"1"}}}' \
  | grep -i '^mcp-session-id' | tr -d '\r' | awk '{print $2}')

if [[ -z "$session" ]]; then
  echo "error: MCP initialize returned no session id — is the token valid?" >&2
  exit 1
fi

curl -sf "${hdr[@]}" -H "Mcp-Session-Id: $session" "$MRR_URL/mcp" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null

# Streamable HTTP answers with SSE, so strip the framing before parsing.
curl -sf --max-time 15 "${hdr[@]}" -H "Mcp-Session-Id: $session" "$MRR_URL/mcp" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  | sed -n 's/^data://p' \
  | python3 -c "
import json, sys
tools = json.load(sys.stdin)['result']['tools']
json.dump({'tools': sorted(tools, key=lambda t: t['name'])}, sys.stdout, indent=2, sort_keys=True)
print()
" > "$OUT/mcp-tools.json"

echo "done"
