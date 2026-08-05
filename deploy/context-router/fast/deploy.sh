#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WORKSPACE_ROOT=${WORKSPACE_HOST_ROOT:-$(CDPATH= cd -- "$SCRIPT_DIR/../../.." && pwd)}
exec "$WORKSPACE_ROOT/deploy/context-router/deploy.sh" project java-server fast
