#!/usr/bin/env bash
set -euo pipefail
BIGFISH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$BIGFISH_ROOT/frontend"
npm ci --registry=https://registry.npmjs.org --cache "$BIGFISH_ROOT/.cache/npm"
npm run build
"$BIGFISH_ROOT/scripts/maven.sh" -Pbundle-ui verify
printf '\nPackaged app: %s/backend/target/bigfish-server-0.1.0-SNAPSHOT.jar\n' "$BIGFISH_ROOT"
