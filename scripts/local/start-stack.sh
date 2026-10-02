#!/usr/bin/env bash
# Foreground, durable-data, loopback-only development stack; no silent H2/S3/AV fallback.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
TOOLS=${TOOL_ROOT:-$ROOT/.tools}
RUNTIME=${RUNTIME_DIR:-$ROOT/.runtime}
export MYSQL_HOME=${MYSQL_HOME:-$TOOLS/mysql-8.4.6-linux-glibc2.28-x86_64-minimal}
export MINIO_TOOLING=${MINIO_TOOLING:-$TOOLS/minio-runtime}
export CLAMAV_HOME=${CLAMAV_HOME:-$TOOLS/clamav-runtime}
export LD_LIBRARY_PATH="$TOOLS/mysql-libs/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$MYSQL_HOME/bin:$TOOLS/redis-7.4.7/src:$PATH"
for tool in java mysqld mysql mysqladmin redis-server redis-cli python3 curl node; do command -v "$tool" >/dev/null || { echo "Missing $tool; see docs/STACK.md" >&2; exit 1; }; done
if [[ ${1:-} == --build ]]; then
  ./mvnw -B verify
  [[ -d cle/node_modules ]] || npm ci --prefix cle
  npm run build --prefix cle
fi
JAR=${APP_JAR:-$ROOT/target/personal-platform-backend-0.6.0.jar}
[[ -f "$JAR" && -f cle/dist/index.html && -d cle/node_modules ]] || { echo 'Build first: scripts/local/start-stack.sh --build' >&2; exit 1; }
mkdir -p "$RUNTIME"/{mysql,redis,uploads,logs,secrets}
chmod 700 "$RUNTIME/secrets"
export RUNTIME
RUNTIME_DIR="$RUNTIME" python3 "$ROOT/scripts/local/init-env.py"
set -a; source "$RUNTIME/secrets/stack.env"; set +a
export DB_URL=${DB_URL:-'jdbc:mysql://127.0.0.1:3306/robust_vision?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false'}
export SERVER_ADDRESS=127.0.0.1 REDIS_HOST=127.0.0.1 REDIS_PORT=6379 QUEUE_MODE=redis
export STORAGE_MODE=${LOCAL_STORAGE_MODE:-minio} ANTIVIRUS_ENABLED=true ANTIVIRUS_REQUIRED=true CLAMAV_HOST=127.0.0.1 CLAMAV_PORT=3310
export STORAGE_ROOT="$RUNTIME/uploads" LEGACY_STORAGE_ROOT="$RUNTIME/uploads"
export SPRING_JPA_HIBERNATE_DDL_AUTO=validate
if [[ "$STORAGE_MODE" == minio ]]; then
  [[ -f "$RUNTIME/secrets/components.env" ]] || { echo 'Run MinIO provisioner first (components.env required).' >&2; exit 1; }
  set -a; source "$RUNTIME/secrets/components.env"; set +a
  export S3_ACCESS_KEY="$MINIO_ROOT_USER" S3_SECRET_KEY="$MINIO_ROOT_PASSWORD"
elif [[ "$STORAGE_MODE" == filesystem ]]; then
  echo 'Explicit partial profile: filesystem object storage. MinIO is NOT running or validated.' >&2
else echo 'LOCAL_STORAGE_MODE must be minio or filesystem' >&2; exit 2; fi
children=()
cleanup(){ trap - EXIT INT TERM; for ((i=${#children[@]}-1;i>=0;i--)); do kill "${children[$i]}" 2>/dev/null || true; done; for p in "${children[@]}"; do wait "$p" 2>/dev/null || true; done; }
trap cleanup EXIT INT TERM
launch(){ local name=$1; shift; "$@" > "$RUNTIME/logs/$name.log" 2>&1 & children+=("$!"); printf '%s\n' "$!" > "$RUNTIME/$name.pid"; }
wait_for(){ local name=$1; shift; for ((n=0;n<180;n++)); do if "$@" >"$RUNTIME/logs/readiness-$name.log" 2>&1; then return; fi; for p in "${children[@]}"; do kill -0 "$p" 2>/dev/null || { echo "$name dependency exited; inspect $RUNTIME/logs" >&2; exit 1; }; done; sleep 1; done; echo "$name not ready after180s; inspect $RUNTIME/logs" >&2; exit 1; }
if [[ ! -d "$RUNTIME/mysql/mysql" ]]; then
  mysqld --no-defaults --initialize-insecure --basedir="$MYSQL_HOME" --datadir="$RUNTIME/mysql" --log-error="$RUNTIME/logs/mysql-init.log"
fi
# Official MySQL TCP-only mode avoids requiring a UNIX-domain socket.
launch mysql mysqld --no-defaults --basedir="$MYSQL_HOME" --datadir="$RUNTIME/mysql" --socket= --pid-file="$RUNTIME/mysql.pid" --bind-address=127.0.0.1 --port=3306 --mysqlx=OFF --log-error="$RUNTIME/logs/mysql-server.log" --innodb-buffer-pool-size=128M --innodb-redo-log-capacity=64M --innodb-numa-interleave=OFF --skip-log-bin
launch redis redis-server --bind 127.0.0.1 --protected-mode yes --port 6379 --appendonly yes --appendfsync everysec --dir "$RUNTIME/redis"
launch clamav "$ROOT/scripts/components/clamav.sh" run
if [[ "$STORAGE_MODE" == minio ]]; then launch minio "$ROOT/scripts/components/minio.sh"; fi
wait_for MySQL mysqladmin --no-defaults --protocol=TCP -h127.0.0.1 ping
if [[ ! -f "$RUNTIME/secrets/db-initialized" ]]; then
  mysql --no-defaults --protocol=TCP -h127.0.0.1 -uroot <<SQL
CREATE DATABASE IF NOT EXISTS robust_vision CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER '$DB_USERNAME'@'127.0.0.1' IDENTIFIED BY '$DB_PASSWORD';
GRANT ALL PRIVILEGES ON robust_vision.* TO '$DB_USERNAME'@'127.0.0.1';
ALTER USER 'root'@'localhost' IDENTIFIED BY '$MYSQL_ROOT_PASSWORD';
SQL
  touch "$RUNTIME/secrets/db-initialized"
fi
wait_for Redis redis-cli -h 127.0.0.1 ping
wait_for ClamAV python3 "$ROOT/scripts/components/clamav-selftest.py" --ping-only
if [[ "$STORAGE_MODE" == minio ]]; then wait_for MinIO curl -fsS http://127.0.0.1:9000/minio/health/ready; fi
launch backend env WORKER_ENABLED=false java -jar "$JAR"
wait_for API curl -fsS http://127.0.0.1:8080/actuator/health/readiness
# API owns migrations/bootstrap; worker validates, then starts consuming Redis.
launch worker env SERVER_PORT=8081 APP_BOOTSTRAP_ENABLED=false SPRING_FLYWAY_ENABLED=false WORKER_ENABLED=true java -jar "$JAR"
launch frontend node cle/node_modules/vite/bin/vite.js preview --config cle/vite.config.ts --outDir "$ROOT/cle/dist" --host 127.0.0.1 --port 4173 --strictPort
wait_for Worker curl -fsS http://127.0.0.1:8081/actuator/health/readiness
wait_for Frontend curl -fsS http://127.0.0.1:4173
printf 'Ready: http://127.0.0.1:4173 (local namespace only); MySQL/Redis/ClamAV + storage=%s; Ctrl-C stops processes, preserves data.\n' "$STORAGE_MODE"
if [[ ${LOCAL_STACK_SHELL:-0} == 1 ]]; then bash --noprofile --norc -i; else wait -n "${children[@]}"; fi
