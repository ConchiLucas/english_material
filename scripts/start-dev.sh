#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="${ROOT_DIR}/.runtime"
IMAGE_STORY_STORAGE_ROOT="${IMAGE_STORY_STORAGE_ROOT:-${RUNTIME_DIR}/image-story}"
mkdir -p "${RUNTIME_DIR}/logs" "${RUNTIME_DIR}/pids" "${IMAGE_STORY_STORAGE_ROOT}"

DB_HOST="${TASK_CENTER_DB_HOST:-127.0.0.1}"
DB_PORT="${TASK_CENTER_DB_PORT:-5432}"
DB_NAME="${TASK_CENTER_DB_NAME:-english_material}"
DB_USER="${TASK_CENTER_DB_USER:-conchi}"
DB_PASSWORD="${TASK_CENTER_DB_PASSWORD:?Set TASK_CENTER_DB_PASSWORD before starting}"
BACKEND_PORT="${TASK_CENTER_SERVER_PORT:-18744}"
FRONTEND_PORT="${TASK_CENTER_FRONTEND_PORT:-19638}"

wait_http() {
  local url="$1"
  for _ in $(seq 1 30); do curl -fsS --max-time 2 "$url" >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}

nc -z "$DB_HOST" "$DB_PORT" || { echo "PostgreSQL is unavailable at ${DB_HOST}:${DB_PORT}"; exit 1; }
mvn -q -DskipTests package
nohup env TASK_CENTER_SERVER_PORT="$BACKEND_PORT" TASK_CENTER_DB_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}" TASK_CENTER_DB_USER="$DB_USER" TASK_CENTER_DB_PASSWORD="$DB_PASSWORD" IMAGE_STORY_STORAGE_ROOT="$IMAGE_STORY_STORAGE_ROOT" java -jar "$ROOT_DIR/target/ai-task-center-0.0.1-SNAPSHOT.jar" >"$RUNTIME_DIR/logs/backend.log" 2>&1 &
echo $! >"$RUNTIME_DIR/pids/backend.pid"
wait_http "http://127.0.0.1:${BACKEND_PORT}/api/ai/config" || { tail -n 80 "$RUNTIME_DIR/logs/backend.log"; exit 1; }
(cd "$ROOT_DIR/web-react" && nohup npm run dev -- --host 0.0.0.0 --port "$FRONTEND_PORT" >"$RUNTIME_DIR/logs/frontend.log" 2>&1 & echo $! >"$RUNTIME_DIR/pids/frontend.pid")
wait_http "http://127.0.0.1:${FRONTEND_PORT}/" || { tail -n 80 "$RUNTIME_DIR/logs/frontend.log"; exit 1; }
echo "Frontend: http://127.0.0.1:${FRONTEND_PORT}/"
echo "Backend:  http://127.0.0.1:${BACKEND_PORT}/"
