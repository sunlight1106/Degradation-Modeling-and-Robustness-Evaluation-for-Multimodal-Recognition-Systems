# Reproducible database and component stack

## Recommended complete stack (Docker)

Prerequisites: Docker Engine and Compose **2.20.2+**, Linux x86_64 (the MinIO source/toolchain lock is x86_64), at least 4GB RAM. The source-build replacement for archived MinIO downloads needs additional first-build disk/time. These Dockerfiles/Compose have been statically checked; Docker is unavailable in the current cloud executor, so the container build and complete MinIO runtime are **not yet executed here**.

1. `sh deploy.sh` securely creates `.env` and builds/starts all services.
2. `scripts/stack.sh stop` stops while preserving all database/object/antivirus volumes.
3. `scripts/stack.sh start` starts already-built images with readiness checks and **no Maven/npm rebuild**.
4. After source edits, `scripts/stack.sh rebuild` rebuilds changed layers.

MySQL8.4 owns the22 application tables; Flyway V1–V7 is the sole schema authority. API migrates and bootstraps first; the independent Redis worker starts only after API readiness, runs Hibernate schema validation and does not rerun migrations or seeds. Backend and worker share one built image. Redis uses AOF. Uploads require real ClamAV; MinIO is private by default and the API idempotently creates its bucket. All data ports remain internal to Compose; only frontend binds127.0.0.1.

Migration validation remains enabled. Never set ddl-auto=create/update against persisted data. Do not edit applied SQL or automatically run Flyway repair/clean. See [database/README.md](../database/README.md) for schema inventory, fresh/upgrade contracts, backup and diagnostics. Back up MySQL, objects and the encryption secrets together. Never use `docker compose down -v` as a restart.

## Maven and build reproducibility

`./mvnw -B verify` (Windows `mvnw.cmd -B verify`) downloads Maven3.9.9 through the official Apache/Maven registry and verifies its SHA-256. Java17 and FFmpeg are required. H2 is test-only. The normal package contains the MySQL driver and Flyway migrations, not an H2 runtime fallback.

The Docker build uses Maven Central directly and its cached repository. The old forced mirror's claimed fallback was ineffective because it replaced `central`; optional regional settings remain in `maven-settings.xml`, but are no longer imposed. Dependency resolution now fails clearly instead of swallowing errors and repeating the same slow download. `./mvnw -B verify` runs correctness tests; opt-in measurement and real-MySQL tests have explicit flags/env documented separately. CI has a real MySQL service contract job.

## Non-root portable development (current Linux x86_64 cloud)

Prerequisites: JDK17, Node22/npm, FFmpeg, Python3, curl, compiler/make, dpkg-deb and compatible Debian system libraries.

```sh
scripts/local/provision-core.sh
MINIO_TOOLING="$PWD/.tools/minio-runtime" scripts/components/minio-build.sh
CLAMAV_HOME="$PWD/.tools/clamav-runtime" scripts/components/clamav-provision.sh
python3 scripts/local/init-env.py
scripts/local/start-stack.sh --build
```

Components install under`.tools`; set TOOL_ROOT to an existing tool root if desired. The launcher defaults to actual MySQL8.4.6, Redis7.4.7, MinIO and ClamAV. It does not silently substitute H2, fake an antivirus result, or disable validation. MySQL's officially supported `--socket=` TCP-only mode is used, with loopback binding. It creates an isolated new database directory and grants the development account only its application database. Generated secrets stay under ignored`.runtime/secrets` with restrictive permissions. Frontend uses Vite's production preview; Docker/Nginx remains the deployment path.

Data persists under`.runtime/mysql`,`.runtime/redis`,`.runtime/minio`,`.runtime/uploads` and component virus-library storage. Ctrl-C stops this launch's child processes; restarting reuses data. Cached launch does not rebuild sources; use`--build` after edits. None of these loopback URLs is a public or user-facing forwarded cloud URL.

### Current verified environment boundary

- Actual MySQL8.4.6 and Redis7.4.7 run; Flyway fresh and populated V6→V7 contracts pass on MySQL.
- ClamAV1.4.3 runs with the complete official signature databases and passes PING, clean INSTREAM and EICAR detection.
- MinIO final official community source `RELEASE.2025-10-15T17-29-55Z` builds with verified modules. However this cloud sandbox forbids the netlink interface enumeration MinIO performs before flag parsing. Even the supported escalation leaves that denial in place. No source patch or security bypass was used. Therefore S3 runtime/full-stack MinIO verification is blocked here.
- `LOCAL_STORAGE_MODE=filesystem scripts/local/start-stack.sh` is an **explicit partial profile** for validating MySQL+Redis+ClamAV+API+worker while the S3 blocker remains. It prints that limitation and keeps mandatory antivirus enabled. Filesystem persistence is not a claim that MinIO passed.

The upstream MinIO community repository and binaries are archived/unsupported. The reproducible source build pins the final security-fixed release; for a production system choose a maintained supported S3 implementation after compatibility testing rather than assuming this archive receives future patches.

## Readiness and safety

`/actuator/health/readiness` requires application-ready state, MySQL, Redis and the configured external upload dependencies. `/actuator/health/liveness` is process liveness. No detailed health or credential information is exposed publicly. Authentication, BCrypt strength, permissions, rate limits and required virus checks are unchanged. Built-in test account reset defaults off to avoid rehashing/resetting it on every restart.

This is a development/research application: model mode remains DEMO unless real provider credentials are explicitly configured. Local payment/SMS flows remain sandbox simulations. The Redis list queue still has no transactional outbox/acknowledgment or crash-recovery lease; a process crash between dequeue and completion can strand work. Bounded queue draining improves backlog throughput without claiming durable exactly-once delivery.

### Run the persisted-data smoke check

For a new isolated local development database with the worker temporarily stopped, export `STACK_PASSWORD` from the generated bootstrap password and explicitly set `MODEL_MODE=demo`. Run `python3 scripts/local/verify-stack.py --stage submit`, then start the worker and run `--stage finish`. After a full component stop/restart, run `--stage persisted`; it reads the saved IDs and verifies exact note contents, task state and downloaded file SHA. The submit stage deliberately creates harmless development records and is not for a production database. The persistent instance left by this task already contains those records and its state file.
