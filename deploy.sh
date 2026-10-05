#!/bin/sh
set -eu
cd "$(dirname "$0")"
docker info >/dev/null
if [ ! -f .env ]; then
  command -v od >/dev/null
  umask 077
  cp .env.example .env
  for key in BOOTSTRAP_ADMIN_PASSWORD MYSQL_PASSWORD MYSQL_ROOT_PASSWORD MINIO_ROOT_PASSWORD JWT_SECRET CREDENTIAL_MASTER_KEY TRAINING_SERVICE_TOKEN; do
    secret=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
    sed "s/^${key}=$/${key}=${secret}/" .env > .env.tmp
    mv .env.tmp .env
  done
  echo 'Generated .env with a random administrator password and service secrets. Keep this file private.'
fi
if ! grep -q '^TRAINING_SERVICE_TOKEN=.' .env; then
  umask 077
  secret=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
  sed '/^TRAINING_SERVICE_TOKEN=/d' .env > .env.tmp
  printf '\nTRAINING_SERVICE_TOKEN=%s\n' "$secret" >> .env.tmp
  mv .env.tmp .env
fi
docker compose up -d --build --wait --wait-timeout 600
port=$(sed -n 's/^WEB_PORT=//p' .env)
echo "Ready: http://localhost:${port:-4173} (administrator credentials are in the local .env file)"
