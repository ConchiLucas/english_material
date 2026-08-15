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

pull_image_with_timeout() {
  image=$1
  pull_timeout=$IMAGE_PULL_TIMEOUT
  pull_elapsed=0

  docker pull --platform "$DOCKER_PLATFORM" "$image" &
  pull_pid=$!

  while kill -0 "$pull_pid" 2>/dev/null; do
    if [ "$pull_elapsed" -ge "$pull_timeout" ]; then
      echo "[WARN] 拉取基础镜像 $image 超过 ${pull_timeout}s，终止拉取进程" >&2
      kill -TERM "$pull_pid" 2>/dev/null || true
      pull_grace=0
      while kill -0 "$pull_pid" 2>/dev/null &&
            [ "$pull_grace" -lt "$IMAGE_PULL_TERMINATION_GRACE" ]; do
        sleep 1
        pull_grace=$((pull_grace + 1))
      done
      if kill -0 "$pull_pid" 2>/dev/null; then
        echo "[WARN] 拉取进程收到 TERM 后仍未退出，发送 KILL" >&2
        kill -KILL "$pull_pid" 2>/dev/null || true
      fi
      wait "$pull_pid" 2>/dev/null || true
      if image_cache_matches_platform "$image"; then
        echo "[WARN] 拉取基础镜像 $image 超时，使用已存在的本地缓存镜像" >&2
        return 0
      fi
      echo "[ERROR] 拉取基础镜像 $image 超时，且本地缓存镜像不存在或平台不匹配" >&2
      return 124
    fi
    sleep 1
    pull_elapsed=$((pull_elapsed + 1))
  done

  if wait "$pull_pid"; then
    return 0
  else
    pull_status=$?
  fi
  if image_cache_matches_platform "$image"; then
    echo "[WARN] 拉取基础镜像 $image 失败，使用已存在的本地缓存镜像" >&2
    return 0
  fi
  echo "[ERROR] 拉取基础镜像 $image 失败，且本地缓存镜像不存在或平台不匹配" >&2
  return "$pull_status"
}

normalize_platform() {
  platform_input=$1
  case "$platform_input" in
    */*) ;;
    *) return 1 ;;
  esac

  platform_os=${platform_input%%/*}
  platform_rest=${platform_input#*/}
  platform_arch=${platform_rest%%/*}
  if [ "$platform_rest" = "$platform_arch" ]; then
    platform_variant=
  else
    platform_variant=${platform_rest#*/}
    case "$platform_variant" in
      ''|*/*) return 1 ;;
    esac
  fi

  case "$platform_os" in
    linux) ;;
    *) return 1 ;;
  esac
  case "$platform_arch" in
    amd64|x86_64|x86-64) platform_arch=amd64 ;;
    arm64|aarch64) platform_arch=arm64 ;;
    arm) platform_arch=arm ;;
    *) return 1 ;;
  esac
  if [ -n "$platform_variant" ]; then
    platform_variant=${platform_variant#v}
    case "$platform_variant" in
      ''|*[!0-9]*) return 1 ;;
    esac
    platform_variant=v$platform_variant
    printf '%s/%s/%s\n' "$platform_os" "$platform_arch" "$platform_variant"
    return 0
  fi
  printf '%s/%s\n' "$platform_os" "$platform_arch"
}

platforms_are_compatible() {
  target_platform=$1
  cached_platform=$2

  target_os=${target_platform%%/*}
  target_rest=${target_platform#*/}
  target_arch=${target_rest%%/*}
  [ "$target_rest" = "$target_arch" ] && target_variant= || target_variant=${target_rest#*/}

  cached_os=${cached_platform%%/*}
  cached_rest=${cached_platform#*/}
  cached_arch=${cached_rest%%/*}
  [ "$cached_rest" = "$cached_arch" ] && cached_variant= || cached_variant=${cached_rest#*/}

  [ "$target_os" = "$cached_os" ] || return 1
  [ "$target_arch" = "$cached_arch" ] || return 1
  if [ -n "$target_variant" ] && [ -n "$cached_variant" ]; then
    [ "$target_variant" = "$cached_variant" ] || return 1
  fi
  return 0
}

image_cache_matches_platform() {
  image=$1
  target_platform=$(normalize_platform "$DOCKER_PLATFORM") || return 1
  cached_platform_raw=$(docker image inspect --format \
    '{{.Os}}/{{.Architecture}}{{if .Variant}}/{{.Variant}}{{end}}' "$image" 2>/dev/null) || return 1
  cached_platform=$(normalize_platform "$cached_platform_raw") || return 1
  platforms_are_compatible "$target_platform" "$cached_platform"
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
export IMAGE_STORY_APP_UID="$APP_UID"
CODEX_CLI_VERSION=${CODEX_CLI_VERSION:-0.144.1}
JAVA_IMAGE=${ENGLISH_MATERIAL_JAVA_IMAGE:-eclipse-temurin:17-jre}
NODE_IMAGE=${ENGLISH_MATERIAL_NODE_IMAGE:-node:22-bookworm-slim}
DOCKER_ARCH=$(docker version --format '{{.Server.Arch}}')
DOCKER_PLATFORM_RAW=${ENGLISH_MATERIAL_DOCKER_PLATFORM:-linux/$DOCKER_ARCH}
if DOCKER_PLATFORM=$(normalize_platform "$DOCKER_PLATFORM_RAW"); then
  :
else
  echo "[ERROR] ENGLISH_MATERIAL_DOCKER_PLATFORM 必须是受支持的 Linux 平台" >&2
  exit 1
fi
IMAGE_PULL_TIMEOUT=${ENGLISH_MATERIAL_IMAGE_PULL_TIMEOUT:-120}
IMAGE_PULL_TERMINATION_GRACE=5
case "$IMAGE_PULL_TIMEOUT" in
  ''|*[!0-9]*)
    echo "[ERROR] ENGLISH_MATERIAL_IMAGE_PULL_TIMEOUT 必须是正整数秒数" >&2
    exit 1
    ;;
esac
if [ "$IMAGE_PULL_TIMEOUT" -le 0 ]; then
  echo "[ERROR] ENGLISH_MATERIAL_IMAGE_PULL_TIMEOUT 必须是正整数秒数" >&2
  exit 1
fi

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
pull_image_with_timeout "$JAVA_IMAGE"
pull_image_with_timeout "$NODE_IMAGE"
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
