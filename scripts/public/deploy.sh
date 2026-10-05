#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
if [ ! -f .env ]; then
  echo 'First run: sh deploy.sh (generates private .env and starts a local-only installation). Then set SITE_DOMAIN in .env.' >&2
  exit 1
fi
domain=$(sed -n 's/^SITE_DOMAIN=//p' .env | tr -d '\r')
# Accept only a plain DNS hostname, never shell text, URLs or Caddy configuration.
case "$domain" in ''|*[!a-zA-Z0-9.-]*|.*|*.) echo 'Set SITE_DOMAIN to a DNS hostname such as study.example.com in .env.' >&2; exit 1;; esac
case "$domain" in *.*) ;; *) echo 'SITE_DOMAIN must contain a dot.' >&2; exit 1;; esac
docker compose -f compose.yaml -f compose.public.yaml config --quiet
# Switching the network requires recreating containers, but keeps all named data volumes.
docker compose -f compose.yaml -f compose.public.yaml down
docker compose -f compose.yaml -f compose.public.yaml up -d --build --wait --wait-timeout 600
printf 'Platform: https://%s\n' "$domain"
echo 'Check the gateway logs and open this address from a different network to verify DNS and certificate issuance.'
