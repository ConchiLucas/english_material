#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WORKSPACE_ROOT=${WORKSPACE_HOST_ROOT:-$(CDPATH= cd -- "$SCRIPT_DIR/../../../.." && pwd)}
ENV_FILE=${ENGLISH_MATERIAL_ENV_FILE:-$WORKSPACE_ROOT/.env.local}

[ -f "$ENV_FILE" ] || { echo "[ERROR] 缺少环境文件：$ENV_FILE" >&2; exit 1; }
export WORKSPACE_HOST_ROOT="$WORKSPACE_ROOT"

echo "[STEP] Full 启动英语材料 Java 后端"
"$WORKSPACE_ROOT/deploy/context-router/full/deploy.sh"

echo "[STEP] Full 启动英语材料 React 前端"
"$WORKSPACE_ROOT/web-react/deploy/context-router/full/deploy.sh"

echo "[PASS] 英语材料工作空间启动成功"
echo "[INFO] 后端：http://127.0.0.1:${ENGLISH_MATERIAL_BACKEND_PORT:-18744}"
echo "[INFO] 前端：http://127.0.0.1:${ENGLISH_MATERIAL_FRONTEND_PORT:-19638}"
