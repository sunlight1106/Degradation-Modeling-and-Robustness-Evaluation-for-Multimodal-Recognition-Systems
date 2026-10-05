#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-status}" in
  build) docker compose build backend minio frontend ;;
  start) docker compose up -d --no-build --wait --wait-timeout 600 ;;
  rebuild) docker compose up -d --build --wait --wait-timeout 600 ;;
  stop) docker compose stop ;;
  status) docker compose ps ;;
  logs) docker compose logs --tail 100 backend model-worker ;;
  *) echo 'Usage: scripts/stack.sh {build|start|rebuild|stop|status|logs}' >&2; exit 2 ;;
esac
