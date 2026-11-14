#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

JAR="target/coding-agent.jar"
NEEDS_BUILD=0
if [[ ! -f "$JAR" ]]; then
  NEEDS_BUILD=1
elif [[ -n "$(find src pom.xml -newer "$JAR" -print -quit 2>/dev/null)" ]]; then
  NEEDS_BUILD=1
fi

if [[ "$NEEDS_BUILD" == "1" ]]; then
  echo "[build] compiling and packaging ..."
  mvn -q -DskipTests package
fi

exec java -jar "$JAR" "$@"
