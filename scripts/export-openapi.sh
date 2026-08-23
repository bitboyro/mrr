#!/usr/bin/env bash
#
# Writes docs/openapi.json from a running mrr.
#
# Called by the build — `mvn verify` boots the app, exports the spec, and stops it again —
# and by scripts/export-contracts.sh for the manual path. Never hand-edit the output.
#
#   MRR_URL=http://localhost:8130 ./scripts/export-openapi.sh
#
set -euo pipefail

MRR_URL="${MRR_URL:-http://localhost:8130}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/docs"
mkdir -p "$OUT"

# Written to a temp file first: a failed export must leave the committed spec alone rather
# than truncating it to nothing.
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

# The server URL is rewritten to a placeholder — it reflects whichever instance generated the
# file, and a tunnel hostname churning in git is pure noise. Sorted keys and a fixed indent
# keep the diff to what actually changed in the API.
curl -sf --max-time 30 "$MRR_URL/v3/api-docs" | python3 -c "
import json, sys
spec = json.load(sys.stdin)
spec['servers'] = [{'url': 'https://mrr.example.com', 'description': 'This mrr instance'}]
json.dump(spec, sys.stdout, indent=2, sort_keys=True)
print()
" > "$tmp"

mv "$tmp" "$OUT/openapi.json"
trap - EXIT
echo "openapi   -> docs/openapi.json"
