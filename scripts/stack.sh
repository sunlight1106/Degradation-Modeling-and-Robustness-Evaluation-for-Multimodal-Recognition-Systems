#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-status}" in
  build) docker compose build backend frontend training ;;
  start) sh deploy.sh --skip-build ;;
  rebuild) sh deploy.sh --build ;;
  full-rebuild) sh deploy.sh --full-build ;;
  stop) docker compose stop ;;
  status) docker compose ps ;;
  logs) docker compose logs --tail 100 backend model-worker ;;
  *) echo 'Usage: scripts/stack.sh {build|start|rebuild|full-rebuild|stop|status|logs}' >&2; exit 2 ;;
esac
