#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

find_project_root() {
  candidate="$SCRIPT_DIR"
  while [ "$candidate" != "/" ]; do
    if [ -f "$candidate/package.json" ] && [ -f "$candidate/package-lock.json" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
    candidate=$(dirname "$candidate")
  done
  echo "[ERROR] 找不到英语材料 React 项目根目录" >&2
  exit 1
}

compose_run() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
  else
    docker-compose "$@"
  fi
}

ensure_shared_network() {
  docker network inspect vibedeploy-shared >/dev/null 2>&1 ||
    docker network create --driver bridge vibedeploy-shared >/dev/null
}

remove_legacy_container() {
  if docker container inspect english-material-frontend-1 >/dev/null 2>&1; then
    echo "[STEP] 移除旧统一 Compose 前端容器 english-material-frontend-1"
    docker rm -f english-material-frontend-1 >/dev/null
  fi
}

PROJECT_ROOT=${PROJECT_ROOT:-${PROJECT_HOST_ROOT:-}}
[ -n "$PROJECT_ROOT" ] || PROJECT_ROOT=$(find_project_root)
WORKSPACE_ROOT=${WORKSPACE_ROOT:-${WORKSPACE_HOST_ROOT:-$(CDPATH= cd -- "$PROJECT_ROOT/.." && pwd)}}
ENV_FILE=${ENGLISH_MATERIAL_ENV_FILE:-$WORKSPACE_ROOT/.env.local}
[ -f "$ENV_FILE" ] || { echo "[ERROR] 缺少环境文件：$ENV_FILE" >&2; exit 1; }
ensure_shared_network

export PROJECT_ROOT PROJECT_HOST_ROOT="$PROJECT_ROOT"
DOCKER_ARCH=$(docker version --format '{{.Server.Arch}}')
DOCKER_PLATFORM=${ENGLISH_MATERIAL_DOCKER_PLATFORM:-linux/$DOCKER_ARCH}
echo "[STEP] 使用当前工作树构建英语材料前端 Full 镜像"
DOCKER_BUILDKIT=1 docker build --pull \
  --platform "$DOCKER_PLATFORM" \
  --build-arg VITE_API_BASE=/api \
  -f "$SCRIPT_DIR/Dockerfile" \
  -t english-material/frontend:local "$PROJECT_ROOT"

remove_legacy_container
echo "[STEP] 启动或替换英语材料前端 Full 容器"
compose_run --env-file "$ENV_FILE" -p english-material-frontend \
  -f "$SCRIPT_DIR/compose.yml" up -d --no-build --force-recreate \
  --wait --wait-timeout "${ENGLISH_MATERIAL_FRONTEND_HEALTH_TIMEOUT:-120}"

echo "[INFO] 英语材料前端 Full 更新完成：http://127.0.0.1:${ENGLISH_MATERIAL_FRONTEND_PORT:-19638}"
