#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$SCRIPT_DIR/image-lib.sh"

find_project_root() {
  candidate="$SCRIPT_DIR"
  while [ "$candidate" != "/" ]; do
    if [ -f "$candidate/pom.xml" ] && [ -d "$candidate/src/main/java" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
    candidate=$(dirname "$candidate")
  done
  echo "[ERROR] 找不到英语材料 Java 项目根目录" >&2
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
  if docker container inspect english-material-backend-1 >/dev/null 2>&1; then
    echo "[STEP] 移除旧统一 Compose 后端容器 english-material-backend-1"
    docker rm -f english-material-backend-1 >/dev/null
  fi
}

wait_for_backend() {
  ready_timeout=${ENGLISH_MATERIAL_READY_TIMEOUT:-180}
  ready_elapsed=0
  while [ "$ready_elapsed" -lt "$ready_timeout" ]; do
    ready_state=$(docker inspect -f '{{.State.Status}}' english-material-backend 2>/dev/null || true)
    ready_health=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' english-material-backend 2>/dev/null || true)
    ready_restarts=$(docker inspect -f '{{.RestartCount}}' english-material-backend 2>/dev/null || true)
    if [ "$ready_state" = running ] && [ "$ready_health" = healthy ] &&
       [ "$ready_restarts" = 0 ] &&
       docker logs english-material-backend 2>&1 | grep -Eq 'Started AiTaskCenterApplication'; then
      return 0
    fi
    sleep 3
    ready_elapsed=$((ready_elapsed + 3))
  done
  echo "[ERROR] 英语材料后端未在 ${ready_timeout}s 内完成启动" >&2
  docker inspect -f 'state={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restarts={{.RestartCount}}' english-material-backend >&2 || true
  docker logs --tail 120 english-material-backend >&2 || true
  return 1
}

PROJECT_ROOT=${PROJECT_ROOT:-${PROJECT_HOST_ROOT:-}}
[ -n "$PROJECT_ROOT" ] || PROJECT_ROOT=$(find_project_root)
WORKSPACE_ROOT=${WORKSPACE_ROOT:-${WORKSPACE_HOST_ROOT:-$PROJECT_ROOT}}
ENV_FILE=${ENGLISH_MATERIAL_ENV_FILE:-$WORKSPACE_ROOT/.env.local}
[ -f "$ENV_FILE" ] || { echo "[ERROR] 缺少环境文件：$ENV_FILE" >&2; exit 1; }

command -v mvn >/dev/null 2>&1 || { echo "[ERROR] 未找到 Maven/JDK 17 构建环境" >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "[ERROR] 未找到 Java 17" >&2; exit 1; }
ensure_shared_network

export WORKSPACE_HOST_ROOT="$WORKSPACE_ROOT"
export PROJECT_HOST_ROOT="$PROJECT_ROOT"
export CODEX_HOST_HOME=${CODEX_HOST_HOME:-$HOME/.codex}
APP_UID=${HOST_UID:-$(id -u)}
CODEX_CLI_VERSION=${CODEX_CLI_VERSION:-0.144.1}
JAVA_IMAGE=${ENGLISH_MATERIAL_JAVA_IMAGE:-eclipse-temurin:17-jre}
NODE_IMAGE=${ENGLISH_MATERIAL_NODE_IMAGE:-node:22-bookworm-slim}
DOCKER_ARCH=$(docker version --format '{{.Server.Arch}}')
DOCKER_PLATFORM=${ENGLISH_MATERIAL_DOCKER_PLATFORM:-linux/$DOCKER_ARCH}

echo "[STEP] 使用当前工作树完整打包英语材料后端"
mvn -B -ntp -U -Dmaven.test.skip=true -f "$PROJECT_ROOT/pom.xml" clean package

JAR_FILE="$PROJECT_ROOT/target/ai-task-center-0.0.1-SNAPSHOT.jar"
LAYERS_DIR="$PROJECT_ROOT/target/context-router-layers"
echo "[STEP] 提取 Spring Boot 分层"
em_prepare_layers "$JAR_FILE" "$LAYERS_DIR"

DEPENDENCIES_HASH=$(em_layer_hash "$LAYERS_DIR/dependencies")
LOADER_HASH=$(em_layer_hash "$LAYERS_DIR/spring-boot-loader")
BASE_DOCKERFILE_HASH=$(em_file_hash "$SCRIPT_DIR/Dockerfile.base")

echo "[STEP] 拉取 Java 17 与 Codex CLI 工具链基础镜像"
docker pull --platform "$DOCKER_PLATFORM" "$JAVA_IMAGE"
docker pull --platform "$DOCKER_PLATFORM" "$NODE_IMAGE"
JAVA_IMAGE_ID=$(docker image inspect --format '{{.Id}}' "$JAVA_IMAGE")
NODE_IMAGE_ID=$(docker image inspect --format '{{.Id}}' "$NODE_IMAGE")
BASE_KEY=$(em_base_key "$DEPENDENCIES_HASH" "$LOADER_HASH" "$BASE_DOCKERFILE_HASH" \
  "$JAVA_IMAGE_ID" "$NODE_IMAGE_ID" "$APP_UID" "$CODEX_CLI_VERSION")
BASE_IMAGE="$EM_BASE_REPOSITORY:$BASE_KEY"
BASE_POINTER_IMAGE="$EM_BASE_REPOSITORY:$EM_BASE_POINTER_TAG"

echo "[STEP] 构建英语材料后端稳定依赖基线：$BASE_IMAGE"
DOCKER_BUILDKIT=1 docker build \
  --platform "$DOCKER_PLATFORM" \
  --build-arg "JAVA_IMAGE=$JAVA_IMAGE" \
  --build-arg "NODE_IMAGE=$NODE_IMAGE" \
  --build-arg "APP_UID=$APP_UID" \
  --build-arg "CODEX_CLI_VERSION=$CODEX_CLI_VERSION" \
  -f "$SCRIPT_DIR/Dockerfile.base" \
  --label "$EM_LABEL_ROLE=dependency-base" \
  --label "$EM_LABEL_KEY=$BASE_KEY" \
  --label "$EM_LABEL_SCHEMA=$EM_BASE_SCHEMA_VERSION" \
  --label "$EM_LABEL_DEPENDENCIES=$DEPENDENCIES_HASH" \
  --label "$EM_LABEL_LOADER=$LOADER_HASH" \
  --label "$EM_LABEL_DOCKERFILE=$BASE_DOCKERFILE_HASH" \
  --label "$EM_LABEL_JAVA_IMAGE=$JAVA_IMAGE_ID" \
  --label "$EM_LABEL_NODE_IMAGE=$NODE_IMAGE_ID" \
  --label "$EM_LABEL_APP_UID=$APP_UID" \
  --label "$EM_LABEL_CODEX_VERSION=$CODEX_CLI_VERSION" \
  -t "$BASE_IMAGE" -t "$BASE_POINTER_IMAGE" "$LAYERS_DIR"

APPLICATION_IMAGE=english-material/backend:local
PREVIOUS_APPLICATION_IMAGE_ID=$(em_image_id "$APPLICATION_IMAGE")
echo "[STEP] 基于依赖基线构建后端 Full 镜像"
em_build_application_image "$BASE_IMAGE" "$LAYERS_DIR" "$APPLICATION_IMAGE" "$BASE_KEY"

remove_legacy_container
echo "[STEP] 启动或替换英语材料后端 Full 容器"
compose_run --env-file "$ENV_FILE" -p english-material-backend \
  -f "$SCRIPT_DIR/compose.yml" up -d --no-build --force-recreate \
  --wait --wait-timeout "${ENGLISH_MATERIAL_HEALTH_TIMEOUT:-180}"
wait_for_backend
em_cleanup_previous_application_image "$PREVIOUS_APPLICATION_IMAGE_ID" "$APPLICATION_IMAGE"

echo "[INFO] 英语材料后端 Full 更新完成：http://127.0.0.1:${ENGLISH_MATERIAL_BACKEND_PORT:-18744}"
