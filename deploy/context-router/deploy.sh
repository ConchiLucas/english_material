#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="${WORKSPACE_HOST_ROOT:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
COMPOSE_FILE="$WORKSPACE_ROOT/deploy/context-router/compose.yaml"
ENV_FILE="$WORKSPACE_ROOT/.env.local"
SCOPE="${1:-workspace}"
TARGET="${2:-start}"
MODE="${3:-full}"

if [[ "$SCOPE" == "workspace" ]]; then
  MODE="$TARGET"
  TARGET="all"
fi
if [[ "$MODE" == "start" ]]; then
  MODE="full"
fi

command -v docker >/dev/null 2>&1 || { echo "[ERROR] docker is required" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "[ERROR] curl is required" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "[ERROR] docker compose is required" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "[ERROR] Docker daemon is unavailable" >&2; exit 1; }

if [[ -f "$ENV_FILE" ]]; then
  set -a
  source "$ENV_FILE"
  set +a
fi

: "${TASK_CENTER_DB_PASSWORD:?Set TASK_CENTER_DB_PASSWORD in $ENV_FILE or the process environment}"
export TASK_CENTER_DB_URL="${TASK_CENTER_DB_URL:-jdbc:postgresql://host.docker.internal:5432/english_material}"
export TASK_CENTER_DB_USER="${TASK_CENTER_DB_USER:-postgres}"
export ENGLISH_MATERIAL_BACKEND_PORT="${ENGLISH_MATERIAL_BACKEND_PORT:-18744}"
export ENGLISH_MATERIAL_FRONTEND_PORT="${ENGLISH_MATERIAL_FRONTEND_PORT:-19638}"
export WORKSPACE_HOST_ROOT="$WORKSPACE_ROOT"
export CODEX_HOST_HOME="${CODEX_HOST_HOME:-$HOME/.codex}"
export HOST_UID="${HOST_UID:-$(id -u)}"

COMPOSE=(docker compose -p english-material -f "$COMPOSE_FILE")
if [[ -f "$ENV_FILE" ]]; then
  COMPOSE=(docker compose --env-file "$ENV_FILE" -p english-material -f "$COMPOSE_FILE")
fi

wait_http() {
  local name="$1"
  local url="$2"
  local service="$3"
  local attempt
  for attempt in $(seq 1 60); do
    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
      echo "[INFO] $name ready: $url"
      return 0
    fi
    sleep 1
  done
  echo "[ERROR] $name did not become ready: $url" >&2
  "${COMPOSE[@]}" logs --tail=100 "$service" >&2 || true
  return 1
}

build_services() {
  if [[ "$MODE" == "full" ]]; then
    DOCKER_BUILDKIT=1 "${COMPOSE[@]}" build --pull "$@"
  elif [[ "$MODE" == "fast" ]]; then
    DOCKER_BUILDKIT=1 "${COMPOSE[@]}" build "$@"
  else
    echo "[ERROR] unsupported deploy mode: $MODE" >&2
    exit 64
  fi
}

start_backend() {
  build_services backend
  "${COMPOSE[@]}" up -d --no-deps --no-build --force-recreate backend
  wait_http "English Material backend" "http://127.0.0.1:${ENGLISH_MATERIAL_BACKEND_PORT}/api/ai/config" backend
}

start_frontend() {
  build_services frontend
  "${COMPOSE[@]}" up -d --no-deps --no-build --force-recreate frontend
  wait_http "English Material frontend" "http://127.0.0.1:${ENGLISH_MATERIAL_FRONTEND_PORT}/" frontend
}

case "$SCOPE:$TARGET" in
  workspace:all)
    start_backend
    start_frontend
    ;;
  project:java-server)
    start_backend
    ;;
  project:web-react)
    start_frontend
    ;;
  *)
    echo "Usage: $0 workspace [start|fast|full] | project {java-server|web-react} {fast|full}" >&2
    exit 64
    ;;
esac

echo "[INFO] deployment completed: scope=$SCOPE target=$TARGET mode=$MODE"
