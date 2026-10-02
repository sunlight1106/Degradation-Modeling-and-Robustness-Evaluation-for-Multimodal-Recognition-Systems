#!/usr/bin/env bash
# Run the final official MinIO community release, built locally from source.
# This is a loopback-only development runtime, not a managed production service.
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TOOLING=${MINIO_TOOLING:-/workspace/shared/tooling/minio-runtime}
if [[ -f "$ROOT/.runtime/secrets/components.env" ]]; then
  set -a
  source "$ROOT/.runtime/secrets/components.env"
  set +a
fi
: "${MINIO_ROOT_USER:?Set MINIO_ROOT_USER or initialize components.env}"
: "${MINIO_ROOT_PASSWORD:?Set MINIO_ROOT_PASSWORD or initialize components.env}"
DATA=${MINIO_DATA_DIR:-$ROOT/.runtime/minio}
mkdir -p "$DATA"
exec "$TOOLING/bin/minio" server "$DATA" \
  --address "127.0.0.1:${MINIO_PORT:-9000}" \
  --console-address "127.0.0.1:${MINIO_CONSOLE_PORT:-9001}"
