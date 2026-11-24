#!/usr/bin/env bash
# Web mirror (web/). Needs the server running on :8787.
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$ROOT/web"
exec npm run dev
