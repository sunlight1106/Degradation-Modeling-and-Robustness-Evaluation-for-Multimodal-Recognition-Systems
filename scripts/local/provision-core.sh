#!/usr/bin/env bash
# Reproducible, unprivileged Linux x86_64 development components.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TOOLS=${TOOL_ROOT:-$ROOT/.tools}
[[ $(uname -m) == x86_64 ]] || { echo 'This portable binary lock requires x86_64. Use Docker Compose on other platforms.' >&2; exit 1; }
mkdir -p "$TOOLS"
fetch() { local name=$1 sha=$2 url=$3; if [[ ! -f "$TOOLS/$name" ]]; then curl -fL --retry 2 "$url" -o "$TOOLS/$name.partial"; printf '%s  %s\n' "$sha" "$TOOLS/$name.partial" | sha256sum -c -; mv "$TOOLS/$name.partial" "$TOOLS/$name"; fi; printf '%s  %s\n' "$sha" "$TOOLS/$name" | sha256sum -c -; }
fetch mysql-8.4.6-minimal.tar.xz f284b17b9e038adbe77f0dd5fb11ed30262286b23a390b8b4e367abc3574c42e https://cdn.mysql.com/archives/mysql-8.4/mysql-8.4.6-linux-glibc2.28-x86_64-minimal.tar.xz
fetch libaio1.deb d1476e4beab3d85f8a7d31de94c27472711ed3ef399ce70708e24b259426125b https://deb.debian.org/debian/pool/main/liba/libaio/libaio1_0.3.113-4_amd64.deb
fetch redis-7.4.7.tar.gz c97e57b0df330a9e091cacff012bebe763c275398cf36ff44cdba876814b595b https://download.redis.io/releases/redis-7.4.7.tar.gz
[[ -x "$TOOLS/mysql-8.4.6-linux-glibc2.28-x86_64-minimal/bin/mysqld" ]] || tar -xf "$TOOLS/mysql-8.4.6-minimal.tar.xz" -C "$TOOLS"
mkdir -p "$TOOLS/mysql-libs"; dpkg-deb -x "$TOOLS/libaio1.deb" "$TOOLS/mysql-libs"
if [[ ! -x "$TOOLS/redis-7.4.7/src/redis-server" ]]; then tar -xf "$TOOLS/redis-7.4.7.tar.gz" -C "$TOOLS"; make -C "$TOOLS/redis-7.4.7" -j2 MALLOC=libc; fi
export LD_LIBRARY_PATH="$TOOLS/mysql-libs/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
"$TOOLS/mysql-8.4.6-linux-glibc2.28-x86_64-minimal/bin/mysqld" --version
"$TOOLS/redis-7.4.7/src/redis-server" --version
echo "Core tools ready in $TOOLS. Run the component provisioners for MinIO/ClamAV, then scripts/local/start-stack.sh."
