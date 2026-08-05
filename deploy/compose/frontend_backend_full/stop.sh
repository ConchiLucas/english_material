#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)

echo "[STEP] stop React frontend"
sh "$ROOT_DIR/deploy/frontend/web_react/local_full/stop.sh"

echo "[STEP] stop Java backend"
sh "$ROOT_DIR/deploy/backend/java_server/local_full/stop.sh"

echo "[STEP] stop Python Worker"
bash "$ROOT_DIR/deploy/backend/python_worker/local_full/stop.sh"

echo "[INFO] ai-task-center full stop completed"
