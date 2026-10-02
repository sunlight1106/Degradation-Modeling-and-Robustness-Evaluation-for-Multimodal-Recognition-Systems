# Implementation validation, 2026-10-02

Validation base: repository commit0195f9b. No deployment was performed.

## Actual database and service checks

- MySQL Community Server8.4.6: real persisted InnoDB data directory, loopback TCP, utf8mb4; never relabelled H2.
- Flyway fresh install and populated V6→V7 upgrade: both pass on MySQL8.4.6. All22 application tables,27foreign keys and20Hibernate entities checked; old migration checksums unchanged; Unicode/decimals/domain rows preserved; rerun is idempotent.
- The original application failed real Hibernate validation on CHAR/VARCHAR mismatches. Explicit mappings fix12fields in7entities; full runtime now keeps schema validation enabled.
- MySQL billing concurrency:4tests pass, covering12parallel first-wallet requests,8distinct recharge settlements to one wallet,12admin adjustments and12usage charges across3wallets/shared provider budget; unauthorized/replayed actions rejected.
- Redis7.4.7: actual Redis with AOF persistence. Worker queue integration and restart checks are recorded below after end-to-end completion.
- ClamAV1.4.3: official complete databases, successful clean INSTREAM and harmless EICAR detection.
- MinIO final official patched release: source build and Go module verification pass. Runtime is blocked by sandbox netlink denial, unchanged after one approved escalated attempt. S3 integration and Docker Compose execution are not claimed as passed.

## Actual MySQL HTTP benchmark

Same500-task/500-file synthetic fixture, two4KiBJSON results/task, same local MySQL schema and account. Original packaged jar retained before edits. Python stdlib persistent HTTP/1.1 connection,5warmups then30timed requests,0.65s spacing, concurrency1. Authentication, BCrypt login and Redis rate limiting remained enabled; every measured response HTTP200, no degraded limiter. API/SQL aggregation outputs agree (500total,167completed,167failed,0.15average confidence lift). Filesystem storage and demo models are configured; dashboard does not access model/storage services.

| Metric | Before | After (initial verified build) | Reduction |
|---|---:|---:|---:|
| Median |104.391ms|50.541ms|51.6%|
| p95 |257.405ms|78.746ms|69.4%|

Raw samples live under ignored`.runtime/evidence/http-before.json` and`http-after.json`. These are single-host observations with30samples, not production capacity guarantees. Final-code repeat: median45.415ms, p9562.234ms (same30samples/5warmups), also allHTTP200. Initial and repeat results are both retained rather than selectively substituted.

Focused in-process measurements in[PERFORMANCE.md](PERFORMANCE.md) separately establish work reduction:505→3SQL and500→5task entities; pixel-identical1080p image transform60.99→39.02ms median. They are H2/component measurements and are not interchangeable with MySQL HTTP numbers.

## Current caveats

Boot3.3.5's managed Flyway10.10 emits an upstream tested-version warning for MySQL8.4 despite the passing real-engine contracts above. This change does not silently upgrade the entire dependency framework; supported-version/security upgrades need their own compatibility run. MinIO community upstream is archived; the pinned final source does not imply ongoing vendor support.

The queue still lacks outbox/acknowledgment recovery and financial usage reservations; the changes do not claim exactly-once jobs or production payment support. Full-list API contracts still return all rows. Dashboard SQL aggregation remains O(N) in qualifying rows; large-history rollups/pagination need a separate compatible design.


### Four concurrent clients

30total measured requests per build,2warmups per worker,4independent persistent HTTP connections; paced between request waves to preserve the120/minute limit. Both application jars were resident against the same MySQL fixture, one exercised at a time. Original median232.138ms/p95365.486ms; final median71.615ms/p9576.828ms. All60measured responses200, no degraded rate limiter. This is a paced concurrent latency check, not maximum throughput/load capacity.

### Warm API startup: no meaningful timing gain established

Three serial alternating original/final process launches with MySQL/Redis already running and an already migrated benchmark database. Timer ends after health and an actual authenticated BCrypt login succeed. Bootstrap/migration execution is disabled identically for this particular measurement; the original needs ddl-auto=none because of its confirmed mapping bug, while the final keeps validate. No lazy initialization, weakened BCrypt, disabled authorization or fake health was used to claim faster readiness.

- Original median9.797s, range9.409–10.355s
- Final median9.830s, range9.580–11.724s

The difference is noise-level and **does not demonstrate faster API process startup**. The repeat-start workflow now reuses built images/jars, builds the API image once for API+worker, omits duplicate worker migrations/seeds and uses explicit readiness polling. These reduce avoidable operational work; Docker warm-start time has not been measured here. First-install downloads, virus-library loading and full-stack cold boot are separate from this API warm-restart benchmark.

## Final build checks

- Full Maven verify:41tests,37passed,0failures/errors,4intentional skips (2opt-in timing methods and2external-MySQL tests). The2MySQL tests plus4accounting tests also ran separately on actualMySQL:6/6pass,0skip.
- Frontend TypeScript checking and Vite production build:passed.
- MavenWrapper fresh download with pinnedSHA-256:passed; reportsMaven3.9.9/JDK17.
- YAML parsing, Python compilation and shell syntax checks:passed. Docker image/Compose execution:not run, since this environment has noDocker.

## End-to-end and persisted restart verification

The final packaged application was tested with actual MySQL, Redis AOF, mandatory ClamAV, filesystem storage (explicit partial profile), separate API/worker processes and production frontend preview. Tests passed:

1. Unauthorized dashboard access returns401; authorized login works.
2. PNG upload is scanned by the real daemon and returnsCLEAN; stored/downloaded SHA-256 matches.
3. With worker stopped, inference POST returnsPENDING and the Redis list length is1.
4. Starting the independent worker changes the task toCOMPLETED/DEMO and empties the queue.
5. A Unicode/emoji note round-trips through MySQL.
6. All processes, including MySQL and Redis, were stopped; the new checked-in local launcher restarted them from persisted directories.
7. Users2/topics6/knowledge entries18/notes1/files2/tasks1 counts remain unchanged. The exact note body, task result and original media SHA remain intact; Redis AOF restores a dedicated persistence-test key.
8. API and worker readiness both returnUP, and the rebuilt frontend serves and proxies the API successfully.

There is no claim that browser rendering, MinIO/S3 or Docker runtime passed. The last two are blocked/unavailable as described above. Real external model inference remains intentionally untested with no keys configured.

Artifacts/scripts: `scripts/local/verify-stack.py`, `scripts/local/start-stack.sh`, `scripts/performance/startup_benchmark.py`, the HTTP benchmark and guarded SQL fixture. Evidence excludes authentication tokens, passwords and database files.
