#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
PID_FILE="$ROOT_DIR/.runtime/pids/python-worker.pid"
LAUNCH_LABEL="com.conchi.ai-task-center.python-worker"

if [ "$(uname -s)" = "Darwin" ] && command -v launchctl >/dev/null 2>&1; then
  launchctl remove "$LAUNCH_LABEL" >/dev/null 2>&1 || true
  rm -f "$PID_FILE"
  echo "[INFO] Python Worker stopped"
  exit 0
fi

if [ ! -f "$PID_FILE" ]; then
  echo "[INFO] Python Worker PID file not found"
  exit 0
fi

PID="$(cat "$PID_FILE")"
if kill -0 "$PID" >/dev/null 2>&1; then
  kill "$PID"
  for _ in $(seq 1 20); do
    if ! kill -0 "$PID" >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
fi

rm -f "$PID_FILE"
echo "[INFO] Python Worker stopped"
