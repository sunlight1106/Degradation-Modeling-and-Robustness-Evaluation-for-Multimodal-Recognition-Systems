#!/usr/bin/env bash
# Synthetic CI-only container: verifies the stock parent config cannot reintroduce raw URL logs.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
IMAGE=nginx:1.27-alpine
docker run --rm --add-host backend:127.0.0.1 -v "$ROOT/cle/nginx.conf:/etc/nginx/conf.d/default.conf:ro" "$IMAGE" nginx -t
container=$(docker run --rm -d --add-host backend:127.0.0.1 -p 127.0.0.1::80 -v "$ROOT/cle/nginx.conf:/etc/nginx/conf.d/default.conf:ro" "$IMAGE")
cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
binding=$(docker port "$container" 80/tcp)
port=${binding##*:}
curl -fsS --retry 10 --retry-connrefused --retry-delay 1 --retry-max-time 20 "http://127.0.0.1:$port/" >/dev/null
status=$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/api/v1/notes/shared/SYNTHETIC_PROXY_PRIVATE_TOKEN?key=SYNTHETIC_PROXY_PRIVATE_TOKEN")
[[ "$status" == 502 ]]
logs=$(docker logs "$container" 2>&1)
if grep -q 'SYNTHETIC_PROXY_PRIVATE_TOKEN' <<<"$logs"; then
    echo 'FAIL: synthetic private URL appeared in Nginx logs' >&2
    exit 1
fi
grep -q '"GET" 502 ' <<<"$logs"
echo 'PASS: Nginx syntax valid and failed upstream request logged without private path/query'
