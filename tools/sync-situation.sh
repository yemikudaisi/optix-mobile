#!/usr/bin/env bash
# Build the Situation map page from the eTracker server repo and copy it into the
# app's assets. Re-run whenever the server repo's map renderer changes.
#
#   tools/sync-situation.sh [path/to/etracker-server-repo]
#
# Default server repo: ../apps/etracker (the bench layout). Needs Node + yarn/npm
# and the server repo's frontend dependencies installed.
set -euo pipefail
cd "$(dirname "$0")/.."
SERVER="${1:-../apps/etracker}"
FRONTEND="$SERVER/frontend"
[ -d "$FRONTEND" ] || { echo "server repo frontend not found at $FRONTEND" >&2; exit 1; }

( cd "$FRONTEND" && npx vite build --config vite.situation.config.js )

DEST=app/src/main/assets/situation
rm -rf "$DEST" && mkdir -p "$DEST"
cp -r "$FRONTEND/dist-situation/." "$DEST/"
echo "copied $(du -sh "$DEST" | cut -f1) to $DEST"
