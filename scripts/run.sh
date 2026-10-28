#!/usr/bin/env bash
set -euo pipefail
BIGFISH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$BIGFISH_ROOT/runtime/worktree.env" ]]; then source "$BIGFISH_ROOT/runtime/worktree.env"; fi
export BIGFISH_DATA_DIR="${BIGFISH_DATA_DIR:-$BIGFISH_ROOT/runtime}"
mkdir -p "$BIGFISH_DATA_DIR"
if [[ ! -x "${JAVA_HOME:-}/bin/java" ]] && [[ -x /usr/libexec/java_home ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; fi
BIGFISH_JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
cd "$BIGFISH_ROOT"
exec "$BIGFISH_JAVA" -jar backend/target/bigfish-server-0.1.0-SNAPSHOT.jar "$@"
