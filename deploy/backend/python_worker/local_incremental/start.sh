#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
WORKER_DIR="$ROOT_DIR/python-worker"

if [ ! -x "$WORKER_DIR/.venv/bin/uvicorn" ]; then
  python3 -m venv "$WORKER_DIR/.venv"
fi
"$WORKER_DIR/.venv/bin/pip" install -q -r "$WORKER_DIR/requirements.txt"

exec bash "$ROOT_DIR/deploy/backend/python_worker/local_full/start.sh"
