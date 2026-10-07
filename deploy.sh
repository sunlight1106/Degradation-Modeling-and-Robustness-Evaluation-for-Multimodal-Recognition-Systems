#!/bin/sh
set -eu
cd "$(dirname "$0")"
mode=${1:-start}
case "$mode" in start|--build|--full-build|--skip-build) ;; *) echo 'Usage: sh deploy.sh [--build|--full-build|--skip-build]' >&2; exit 2 ;; esac
if [ "$#" -gt 1 ]; then echo 'Choose one startup mode.' >&2; exit 2; fi
started=$(date +%s)
docker info >/dev/null
docker_ready=$(date +%s)
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
required=$(docker compose config --services)
installed=$(docker compose ps --all --format '{{.Service}}')
missing=false
for service in $required; do
  if ! printf '%s\n' "$installed" | grep -Fqx "$service"; then missing=true; fi
done
if [ "$mode" = --full-build ] || [ "$missing" = true ]; then
  if [ "$mode" = --skip-build ]; then echo 'Incomplete installation. Run sh deploy.sh once without --skip-build.' >&2; exit 1; fi
  echo 'Installing services / applying full configuration. Dependencies may be downloaded and compiled.'
  docker compose up -d --build --wait --wait-timeout 600
elif [ "$mode" = --build ]; then
  echo 'Building application changes; installed storage services are retained.'
  docker compose build backend frontend training
  docker compose stop frontend model-worker backend
  docker compose start --wait --wait-timeout 600 mysql redis minio clamav
  docker compose up -d --no-deps --no-build --wait --wait-timeout 240 backend model-worker training
  docker compose up -d --no-deps --no-build --wait --wait-timeout 240 frontend
else
  echo 'Starting installed services without rebuilding, downloading or recreating containers.'
  docker compose start --wait --wait-timeout 600
  echo 'After changing code, run: sh deploy.sh --build'
fi
ready=$(date +%s)
port=$(sed -n 's/^WEB_PORT=//p' .env)
echo "Ready: http://localhost:${port:-4173} (administrator credentials are in the local .env file)"
echo "Startup: Docker $((docker_ready-started))s; project $((ready-docker_ready))s; total $((ready-started))s."
