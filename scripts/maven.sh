#!/usr/bin/env bash
set -euo pipefail
BIGFISH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ! -x "${JAVA_HOME:-}/bin/java" ]]; then
  if [[ -x /usr/libexec/java_home ]]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  else
    echo 'Please set JAVA_HOME to a Java 21 installation.' >&2
    exit 1
  fi
fi
cd "$BIGFISH_ROOT"
exec mvn -gs "$BIGFISH_ROOT/.mvn/settings.xml" -s "$BIGFISH_ROOT/.mvn/settings.xml" -Dmaven.repo.local="$BIGFISH_ROOT/.cache/maven" "$@"
