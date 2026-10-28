#!/usr/bin/env bash
set -euo pipefail
BIGFISH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$BIGFISH_ROOT/runtime/worktree.env" ]]; then source "$BIGFISH_ROOT/runtime/worktree.env"; fi
cd "$BIGFISH_ROOT/frontend"
if [[ ! -d node_modules ]]; then npm ci --registry=https://registry.npmjs.org --cache "$BIGFISH_ROOT/.cache/npm"; fi
exec npm run dev
