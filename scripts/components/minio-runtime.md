# Portable MinIO runtime

This builds real MinIO from the final official release, without requiring Docker
or root. It does not implement or emulate an S3 server.

## Provenance and maintenance warning

- Official source: `https://github.com/minio/minio`, tag
  `RELEASE.2025-10-15T17-29-55Z`, commit
  `9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a`.
- Archive SHA-256: `be6d0bd3696c3a13a35f02d3a0280b64319c67918b4501c5c3d87f96d000085c`.
- Build toolchain: official Go 1.27.1 linux-amd64 archive,
  SHA-256 `63d339f0da5ab53635a56f2490a7984dfe12dfcff22ad749f63edaf590168445`.
  The toolchain checksum was verified against Go's download metadata.
- Dependencies remain pinned by upstream `go.mod`/`go.sum`; `go mod verify`
  checks the module cache after compilation.

This release fixes the privilege-escalation issue disclosed in the final upstream
release. The MinIO community repository was subsequently archived, and the
official binary endpoint now returns HTTP 410 with an unsupported-project notice.
There are **no ongoing upstream security updates**. This runtime must not be
presented as a supported production deployment.

## Build and launch

```sh
scripts/components/minio-build.sh
scripts/components/minio.sh
```

The source builder defaults to `/workspace/shared/tooling/minio-runtime`; override
`MINIO_TOOLING` when needed. The server launcher binds only `127.0.0.1:9000` and
`127.0.0.1:9001`. Set `MINIO_PORT` and `MINIO_CONSOLE_PORT` to change the ports.
Persistent data defaults to the repository's ignored `.runtime/minio` directory;
override `MINIO_DATA_DIR` when needed.

The launcher reads `.runtime/secrets/components.env` if it exists, otherwise
expects `MINIO_ROOT_USER` and `MINIO_ROOT_PASSWORD` in its environment. Secrets
must not be committed. Keep the credentials file restricted to its owner.

In a shell that shares the server's network namespace:

```sh
set -a
source .runtime/secrets/components.env
set +a
python3 scripts/components/minio-selftest.py
```

This creates the configured private bucket if needed, writes/reads a uniquely
named harmless object, verifies anonymous access is denied, and removes only that
self-test object. `S3_ENDPOINT` defaults to `http://127.0.0.1:9000`; `S3_BUCKET`
defaults to `personal-platform`. The self-test rejects remote endpoints.

For the application, use `STORAGE_MODE=minio`, `S3_ENDPOINT`, `S3_BUCKET`,
`S3_ACCESS_KEY=$MINIO_ROOT_USER`, and `S3_SECRET_KEY=$MINIO_ROOT_PASSWORD`.

## Container alternative

`scripts/components/Minio.Dockerfile` builds the same pinned upstream source and
toolchain, then installs the compiled server into a small Debian runtime with
CA certificates and curl for health checks. It runs as non-root UID/GID 10001.

```sh
docker build -f scripts/components/Minio.Dockerfile -t robust-vision-minio:source .
```

The application already initializes its configured S3 bucket. A separate `mc`
image is therefore not necessary when the application uses that initialization
path. Do not publish the MinIO ports externally without reviewing deployment
security and the unsupported-upstream warning above.

## Verification limit in the dot cloud sandbox

Compilation completed successfully and all downloaded Go modules verified.
However, this environment denies netlink interface discovery. Unmodified MinIO
enumerates interfaces in package initialization, before CLI arguments are read,
and exits with `route ip+net: netlinkrib: operation not permitted`. Even
`minio --version` hits this upstream initialization path. Explicit loopback
addresses do not avoid that requirement.

No sandbox restriction was weakened, no fake network-interface implementation
was added, and a running MinIO service or successful S3 test must not be claimed
until startup succeeds in a permitted execution environment. The Dockerfile is
provided for an environment that supports standard container networking; no
Docker command is available in this sandbox to build or test it.

The supported tool-level escalated retry was also attempted once and returned
the identical netlink denial. Build and runtime evidence are recorded in the
tooling directory's `provision-verification.log` and `runtime-check.json`.
