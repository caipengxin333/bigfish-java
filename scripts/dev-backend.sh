#!/usr/bin/env bash
set -euo pipefail
BIGFISH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Per-worktree settings never contain API keys.
if [[ -f "$BIGFISH_ROOT/runtime/worktree.env" ]]; then source "$BIGFISH_ROOT/runtime/worktree.env"; fi
export BIGFISH_DATA_DIR="${BIGFISH_DATA_DIR:-$BIGFISH_ROOT/runtime}"
mkdir -p "$BIGFISH_DATA_DIR"
exec "$BIGFISH_ROOT/scripts/maven.sh" -pl backend spring-boot:run "$@"
