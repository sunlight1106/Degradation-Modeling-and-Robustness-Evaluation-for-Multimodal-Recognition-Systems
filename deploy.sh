#!/bin/sh
set -eu
cd "$(dirname "$0")"
docker info >/dev/null
if [ ! -f .env ]; then
  command -v od >/dev/null
  umask 077
  cp .env.example .env
  for key in MYSQL_PASSWORD MYSQL_ROOT_PASSWORD MINIO_ROOT_PASSWORD JWT_SECRET CREDENTIAL_MASTER_KEY; do
    secret=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
    sed "s/^${key}=$/${key}=${secret}/" .env > .env.tmp
    mv .env.tmp .env
  done
  echo 'Generated .env (random DB/JWT secrets). Admin password uses the fixed default from .env.example.'
fi
docker compose up -d --build --wait --wait-timeout 600
port=$(sed -n 's/^WEB_PORT=//p' .env)
echo "Ready: http://localhost:${port:-4173} (username admin; password 1926648785ljz)"
