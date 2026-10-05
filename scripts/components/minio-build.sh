#!/usr/bin/env bash
# Rebuild the final official MinIO community release without Docker or root.
# Download sources are official Go and MinIO endpoints only; hashes are pinned.
set -euo pipefail
BASE=${MINIO_TOOLING:-/workspace/shared/tooling/minio-runtime}
RELEASE=RELEASE.2025-10-15T17-29-55Z
COMMIT=9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a
GO_ARCHIVE=go1.27.1.linux-amd64.tar.gz
GO_SHA256=63d339f0da5ab53635a56f2490a7984dfe12dfcff22ad749f63edaf590168445
# SHA-256 of the upstream HTTPS tag archive as retrieved 2026-10-02.
SOURCE_SHA256=be6d0bd3696c3a13a35f02d3a0280b64319c67918b4501c5c3d87f96d000085c
mkdir -p "$BASE"/{bin,gopath,gocache}
cd "$BASE"
[[ -f "$GO_ARCHIVE" ]] || curl --fail --location --retry 2 --output "$GO_ARCHIVE" "https://go.dev/dl/$GO_ARCHIVE"
printf '%s  %s\n' "$GO_SHA256" "$GO_ARCHIVE" | sha256sum --check -
[[ -f minio-source.tar.gz ]] || curl --fail --location --retry 2 --output minio-source.tar.gz "https://codeload.github.com/minio/minio/tar.gz/refs/tags/$RELEASE"
printf '%s  %s\n' "$SOURCE_SHA256" minio-source.tar.gz | sha256sum --check -
[[ -x go/bin/go ]] || tar -xzf "$GO_ARCHIVE"
[[ -d "minio-$RELEASE" ]] || tar -xzf minio-source.tar.gz
export PATH="$BASE/go/bin:$PATH" GOPATH="$BASE/gopath" GOCACHE="$BASE/gocache"
export GOTOOLCHAIN=local CGO_ENABLED=0 GOMAXPROCS=${GOMAXPROCS:-3}
cd "minio-$RELEASE"
go build -buildvcs=false -tags kqueue -p "$GOMAXPROCS" -trimpath \
  -ldflags "-s -w -X github.com/minio/minio/cmd.Version=2025-10-15T17:29:55Z -X github.com/minio/minio/cmd.ReleaseTag=$RELEASE -X github.com/minio/minio/cmd.CommitID=$COMMIT -X github.com/minio/minio/cmd.ShortCommitID=${COMMIT:0:12}" \
  -o "$BASE/bin/minio" .
go mod verify
# Inspect embedded metadata without executing the server's network discovery.
go version -m "$BASE/bin/minio"
sha256sum "$BASE/bin/minio" > "$BASE/minio-binary.sha256"
