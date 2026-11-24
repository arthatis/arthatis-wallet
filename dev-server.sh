#!/usr/bin/env bash
# Sync server (server/). Needs Node >= 24.
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$ROOT/server"
exec npm run dev
