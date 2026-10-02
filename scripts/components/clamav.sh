#!/usr/bin/env bash
# Portable, loopback-only ClamAV runtime for Debian 13 / x86_64.
set -euo pipefail
BASE="${CLAMAV_HOME:-/workspace/shared/tooling/clamav-runtime}"
PORT="${CLAMAV_PORT:-3310}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export LD_LIBRARY_PATH="$BASE/root/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
[[ "$PORT" =~ ^[0-9]+$ ]] && (( PORT > 0 && PORT < 65536 )) || { echo 'Invalid CLAMAV_PORT' >&2; exit 2; }
configure() {
  mkdir -p "$BASE"/{data,logs,run}
  cat > "$BASE/clamd.conf" <<CONFIG
DatabaseDirectory $BASE/data
LogFile $BASE/logs/clamd.log
LogTime yes
PidFile $BASE/run/clamd.pid
TemporaryDirectory $BASE/run
TCPSocket $PORT
TCPAddr 127.0.0.1
Foreground yes
User $(id -un)
StreamMaxLength 100M
MaxFileSize 100M
MaxScanSize 200M
MaxThreads 4
SelfCheck 600
CONFIG
  cat > "$BASE/freshclam.conf" <<CONFIG
DatabaseDirectory $BASE/data
DatabaseOwner $(id -un)
UpdateLogFile $BASE/logs/freshclam.log
LogTime yes
Foreground yes
DatabaseMirror database.clamav.net
DNSDatabaseInfo current.cvd.clamav.net
ConnectTimeout 30
ReceiveTimeout 60
Checks 12
CONFIG
}
ensure_database() {
  local db
  for db in main daily bytecode; do
    [[ -s "$BASE/data/$db.cvd" || -s "$BASE/data/$db.cld" ]] || { echo "Missing official $db database; run $0 update" >&2; exit 1; }
  done
}
case "${1:-status}" in
  configure) configure ;;
  update)
    configure
    exec "$BASE/root/usr/bin/freshclam" --config-file="$BASE/freshclam.conf"
    ;;
  update-daemon)
    configure
    exec "$BASE/root/usr/bin/freshclam" --config-file="$BASE/freshclam.conf" --daemon --foreground
    ;;
  run)
    configure
    ensure_database
    exec "$BASE/root/usr/sbin/clamd" --config-file="$BASE/clamd.conf" --foreground
    ;;
  start)
    configure
    ensure_database
    if python3 "$SCRIPT_DIR/clamav-selftest.py" --port "$PORT" --ping-only >/dev/null 2>&1; then
      echo "ClamAV already responds on 127.0.0.1:$PORT"
      exit 0
    fi
    nohup "$BASE/root/usr/sbin/clamd" --config-file="$BASE/clamd.conf" --foreground >"$BASE/logs/clamd-console.log" 2>&1 < /dev/null &
    child=$!
    for ((i=0;i<120;i++)); do
      if python3 "$SCRIPT_DIR/clamav-selftest.py" --port "$PORT" --ping-only >/dev/null 2>&1; then
        echo "ClamAV ready on 127.0.0.1:$PORT (PID $child)"
        exit 0
      fi
      if ! kill -0 "$child" 2>/dev/null; then cat "$BASE/logs/clamd-console.log" >&2; exit 1; fi
      sleep 1
    done
    echo "ClamAV is still starting; inspect $BASE/logs/clamd-console.log" >&2
    exit 1
    ;;
  status)
    exec python3 "$SCRIPT_DIR/clamav-selftest.py" --port "$PORT" --ping-only
    ;;
  self-test)
    exec python3 "$SCRIPT_DIR/clamav-selftest.py" --port "$PORT"
    ;;
  stop)
    if [[ ! -f "$BASE/run/clamd.pid" ]]; then echo 'No ClamAV PID file'; exit 0; fi
    pid="$(cat "$BASE/run/clamd.pid")"
    [[ "$pid" =~ ^[0-9]+$ ]] || { echo 'Invalid ClamAV PID' >&2; exit 1; }
    if ! kill -0 "$pid" 2>/dev/null; then echo 'ClamAV is not running'; exit 0; fi
    [[ "$(readlink "/proc/$pid/exe")" == "$BASE/root/usr/sbin/clamd" ]] || { echo 'PID is not this ClamAV executable; refusing to stop' >&2; exit 1; }
    kill "$pid"
    ;;
  *) echo "Usage: $0 {configure|update|update-daemon|run|start|status|self-test|stop}" >&2; exit 2 ;;
esac
