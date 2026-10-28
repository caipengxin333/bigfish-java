#!/usr/bin/env bash
set -euo pipefail
BIGFISH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIGFISH_PARENT="$(dirname "$BIGFISH_ROOT")/bigfish-worktrees"
cd "$BIGFISH_ROOT"
git rev-parse --verify HEAD >/dev/null
mkdir -p "$BIGFISH_PARENT"
for role in backend frontend collector; do
  case "$role" in
    backend) branch=feature/backend-core; port=18081; ui=15174 ;;
    frontend) branch=feature/frontend-stage; port=18082; ui=15175 ;;
    collector) branch=feature/douyin-collector; port=18083; ui=15176 ;;
  esac
  target="$BIGFISH_PARENT/$role"
  if [[ -e "$target" ]]; then
    actual="$(git -C "$target" branch --show-current)"
    [[ "$actual" == "$branch" ]] || { echo "Existing path has unexpected branch: $target" >&2; exit 1; }
    [[ "$(git -C "$target" rev-parse --path-format=absolute --git-common-dir)" == "$(git rev-parse --path-format=absolute --git-common-dir)" ]] || { echo 'Existing path belongs to another repository.' >&2; exit 1; }
  elif git show-ref --verify --quiet "refs/heads/$branch"; then
    git worktree add "$target" "$branch"
  else
    git worktree add -b "$branch" "$target" HEAD
  fi
  mkdir -p "$target/runtime"
  if [[ ! -f "$target/runtime/worktree.env" ]]; then
    # %q safely quotes workspace paths containing spaces or non-ASCII text.
    printf 'export BIGFISH_PORT=%q\nexport BIGFISH_UI_PORT=%q\nexport BIGFISH_API_TARGET=%q\nexport BIGFISH_DATA_DIR=%q\n' \
      "$port" "$ui" "http://127.0.0.1:$port" "$target/runtime" > "$target/runtime/worktree.env"
  fi
  printf '%s: %s (%s), API=%s UI=%s\n' "$role" "$target" "$branch" "$port" "$ui"
done
git worktree list
