#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
WORKER_DIR="$ROOT_DIR/python-worker"
RUNTIME_DIR="$ROOT_DIR/.runtime"
LOG_DIR="$RUNTIME_DIR/logs"
PID_DIR="$RUNTIME_DIR/pids"
LOG_FILE="$LOG_DIR/python-worker.log"
PID_FILE="$PID_DIR/python-worker.pid"
PORT="${PYTHON_WORKER_PORT:-10052}"
HEALTH_URL="http://127.0.0.1:${PORT}/api/health"
LAUNCH_LABEL="com.conchi.ai-task-center.python-worker"

mkdir -p "$LOG_DIR" "$PID_DIR"

if curl -fsS --max-time 2 "$HEALTH_URL" >/dev/null 2>&1; then
  echo "[INFO] Python Worker already running: $HEALTH_URL"
  exit 0
fi

if [ ! -x "$WORKER_DIR/.venv/bin/uvicorn" ]; then
  python3 -m venv "$WORKER_DIR/.venv"
  "$WORKER_DIR/.venv/bin/pip" install -r "$WORKER_DIR/requirements.txt"
fi

DB_HOST="${TASK_CENTER_DB_HOST:-127.0.0.1}"
DB_PORT="${TASK_CENTER_DB_PORT:-5432}"
DB_NAME="${TASK_CENTER_DB_NAME:-ai_task_center}"
DB_USER="${TASK_CENTER_DB_USER:-conchi}"
DB_PASSWORD="${TASK_CENTER_DB_PASSWORD:-conchi123456}"
PUBLIC_URL="${PYTHON_WORKER_PUBLIC_BASE_URL:-http://127.0.0.1:${PORT}}"

if [ "$(uname -s)" = "Darwin" ] && command -v launchctl >/dev/null 2>&1; then
  launchctl remove "$LAUNCH_LABEL" >/dev/null 2>&1 || true
  launchctl submit -l "$LAUNCH_LABEL" -o "$LOG_FILE" -e "$LOG_FILE" -- \
    /usr/bin/env \
      TASK_CENTER_DB_HOST="$DB_HOST" \
      TASK_CENTER_DB_PORT="$DB_PORT" \
      TASK_CENTER_DB_NAME="$DB_NAME" \
      TASK_CENTER_DB_USER="$DB_USER" \
      TASK_CENTER_DB_PASSWORD="$DB_PASSWORD" \
      TASK_QUEUE_MAX_WORKERS="${TASK_QUEUE_MAX_WORKERS:-8}" \
      TASK_QUEUE_POLL_SECONDS="${TASK_QUEUE_POLL_SECONDS:-1}" \
      TASK_QUEUE_LEASE_SECONDS="${TASK_QUEUE_LEASE_SECONDS:-900}" \
      TASK_QUEUE_HEARTBEAT_SECONDS="${TASK_QUEUE_HEARTBEAT_SECONDS:-30}" \
      PYTHON_WORKER_PUBLIC_BASE_URL="$PUBLIC_URL" \
      "$WORKER_DIR/.venv/bin/uvicorn" app.main:app \
        --app-dir "$WORKER_DIR" --host 0.0.0.0 --port "$PORT"
  rm -f "$PID_FILE"
else
  cd "$WORKER_DIR"
  nohup env \
    TASK_CENTER_DB_HOST="$DB_HOST" \
    TASK_CENTER_DB_PORT="$DB_PORT" \
    TASK_CENTER_DB_NAME="$DB_NAME" \
    TASK_CENTER_DB_USER="$DB_USER" \
    TASK_CENTER_DB_PASSWORD="$DB_PASSWORD" \
    TASK_QUEUE_MAX_WORKERS="${TASK_QUEUE_MAX_WORKERS:-8}" \
    TASK_QUEUE_POLL_SECONDS="${TASK_QUEUE_POLL_SECONDS:-1}" \
    TASK_QUEUE_LEASE_SECONDS="${TASK_QUEUE_LEASE_SECONDS:-900}" \
    TASK_QUEUE_HEARTBEAT_SECONDS="${TASK_QUEUE_HEARTBEAT_SECONDS:-30}" \
    PYTHON_WORKER_PUBLIC_BASE_URL="$PUBLIC_URL" \
    "$WORKER_DIR/.venv/bin/uvicorn" app.main:app --host 0.0.0.0 --port "$PORT" \
    >"$LOG_FILE" 2>&1 &
  WORKER_PID=$!
  echo "$WORKER_PID" >"$PID_FILE"
fi

for _ in $(seq 1 60); do
  if curl -fsS --max-time 2 "$HEALTH_URL" >/dev/null 2>&1; then
    echo "[INFO] Python Worker ready: $HEALTH_URL"
    exit 0
  fi
  sleep 1
done

tail -n 80 "$LOG_FILE" >&2 || true
if [ "$(uname -s)" = "Darwin" ] && command -v launchctl >/dev/null 2>&1; then
  launchctl remove "$LAUNCH_LABEL" >/dev/null 2>&1 || true
elif [ -n "${WORKER_PID:-}" ]; then
  kill "$WORKER_PID" >/dev/null 2>&1 || true
fi
exit 1
