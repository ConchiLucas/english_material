#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)

echo "[STEP] incrementally start Python Worker"
bash "$ROOT_DIR/deploy/backend/python_worker/local_incremental/start.sh"

echo "[STEP] incrementally start Java backend"
sh "$ROOT_DIR/deploy/backend/java_server/local_incremental/start.sh"

echo "[STEP] start React frontend"
sh "$ROOT_DIR/deploy/frontend/web_react/local_full/start.sh"

echo "[INFO] ai-task-center incremental deploy completed"
