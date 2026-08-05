#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/../../../.." && pwd)
MAVEN_BIN=${MAVEN_BIN:-$(command -v mvn || true)}

if [ -z "$MAVEN_BIN" ] && [ -x "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn" ]; then
  MAVEN_BIN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
fi
if [ -z "$MAVEN_BIN" ]; then
  echo "Maven executable was not found. Set MAVEN_BIN before deploying." >&2
  exit 1
fi

(cd "$ROOT_DIR" && "$MAVEN_BIN" -q -DskipTests clean package)

docker compose \
  -p ai-task-center-server \
  -f "$SCRIPT_DIR/docker-compose.yml" \
  up --build -d
