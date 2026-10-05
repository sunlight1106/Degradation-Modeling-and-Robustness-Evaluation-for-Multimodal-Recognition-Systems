#!/usr/bin/env bash
# Extract official Debian packages locally, authenticate every archive, fetch
# and validate the complete official database using FreshClam. Requires Debian
# 13 x86_64, curl, dpkg-deb, sha256sum, Python 3, and system library dependencies.
set -euo pipefail
BASE="${CLAMAV_HOME:-/workspace/shared/tooling/clamav-runtime}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
[[ "$(uname -m)" == x86_64 ]] || { echo 'This package lock is for x86_64' >&2; exit 1; }
mkdir -p "$BASE"/{debs,root,data,logs,run}
while IFS=$'\t' read -r digest name url; do
  [[ -z "$digest" || "$digest" == \#* ]] && continue
  archive="$BASE/debs/$name"
  if [[ ! -f "$archive" ]]; then
    curl --fail --location --retry 2 --connect-timeout 30 --max-time 300 "$url" -o "$archive.partial"
    printf '%s  %s\n' "$digest" "$archive.partial" | sha256sum -c -
    mv "$archive.partial" "$archive"
  fi
  printf '%s  %s\n' "$digest" "$archive" | sha256sum -c -
  dpkg-deb -x "$archive" "$BASE/root"
done < "$SCRIPT_DIR/clamav-packages.tsv"
export CLAMAV_HOME="$BASE"
"$SCRIPT_DIR/clamav.sh" configure
export LD_LIBRARY_PATH="$BASE/root/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
if ldd "$BASE/root/usr/sbin/clamd" | grep 'not found'; then
  echo 'Missing host library dependency; install/extract the official Debian package first.' >&2
  exit 1
fi
"$BASE/root/usr/sbin/clamd" --config-file="$BASE/clamd.conf" --version
"$SCRIPT_DIR/clamav.sh" update
for db in "$BASE"/data/*.cvd; do "$BASE/root/usr/bin/sigtool" --info "$db"; done
printf 'ClamAV provisioned at %s\n' "$BASE"
