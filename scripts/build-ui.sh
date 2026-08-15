#!/usr/bin/env bash
# Build the Next static export into src/main/resources/static/ for a local jar.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/ui"
npm install
npm run build
rm -rf "$root/src/main/resources/static"
mkdir -p "$root/src/main/resources/static"
cp -R out/. "$root/src/main/resources/static/"
echo "UI copied to src/main/resources/static/"
