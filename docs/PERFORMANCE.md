# Performance work and reproducible checks

## What changed

- Dashboard: permission-scoped SQL aggregates plus a `LIMIT 5` latest-task fetch. Only five task JSON payload pairs are decoded instead of every historical result. One read-only transaction preserves a coherent database view; no cross-request summary or authorization cache is used.
- Inference lists: explicit to-one fetch graphs remove file/model/requester N+1 loads while preserving the existing response contract. Dashboard fetching never joins a collection, so its limit is applied in SQL.
- Authentication lookup: load user, role and permissions together rather than separate eager-load queries. User status/permissions are still retrieved afresh on every authenticated request.
- Knowledge: grouped entry/reference counts replace per-topic/per-card queries; topic-visible lists and entry-owner-visible searches retain their existing distinct visibility rules and ordering. Choosing the next topic order uses SQL `MAX` rather than materializing all topics.
- Workspaces: scalar summaries and batched member/permission rows are grouped once per request. No eager user/role entities are loaded solely for list DTOs; membership revocations are visible on the next request.
- Inbox/sent messages: scalar message summaries plus batched recipient/attachment projections replace per-message child queries and user/role/file hydration. Batches are bounded to 500 IDs. Read flags, recipient/attachment order, ownership visibility and detail mark-read remain unchanged.
- Notes: share counts are grouped in bounded batches rather than loading every share for every note; revoked and expired shares are still counted, matching the prior response contract.
- Images: a 256-entry channel lookup and reusable scanline replace three floating-point transforms plus per-pixel input/output calls. The algorithm is pixel-identical, including transparency, premultiplied/indexed/grayscale images and subimages. Temporary working storage is one scanline; the output image allocation is unchanged.
- Redis worker: drain up to 32 jobs per scheduled tick, fetching each only when ready to execute it. This removes the fixed 350 ms scheduling gap between jobs already waiting in a backlog. Empty-queue polling remains unchanged. `app.worker.max-tasks-per-poll` can be configured from 1 to 1024. This is a throughput optimization, **not** an acknowledgement/retry/outbox reliability implementation.
- Model adapters: lazily reuse credential-free HTTP clients per endpoint, retaining request-specific authentication/rotation. Kimi Files-API video requests no longer allocate a Base64 data URL that is never sent. Runtime capability inspection reads/decrypts each provider's key list once instead of twice; nothing is cached across requests.

## Focused benchmark (2026-10-02)

Environment: Linux cloud container, Temurin Java 17.0.20.1, Spring Boot 3.3.5 / Hibernate 6.5.3, in-memory H2 in MySQL compatibility mode. No external model calls. Timings are indicative of this machine, not production capacity or a remote MySQL/HTTP latency claim.

Each benchmark warms both implementations five times and measures 15 alternating samples. The dashboard test clears the persistence context and Hibernate statistics before each operation. Its baseline preserves the original un-fetched JPQL + full DTO conversion + Java aggregation implementation inside the test. The current implementation executes against identical fixtures in the same JVM and transaction. Both complete DTO results must match. SQL counts and loaded entities are asserted separately from timing, which is intentionally not a flaky CI threshold.

| Workload | Original median | Optimized median | Original p95 | Optimized p95 | Work reduction |
| --- | ---: | ---: | ---: | ---: | --- |
| Dashboard, 500 tasks, unique input files, two 4 KiB JSON results/task | 30.977 ms | 6.375 ms | 76.277 ms | 10.918 ms | 505 → 3 SQL statements; 500 → 5 task entity loads |
| 1920 × 1080, `TYPE_3BYTE_BGR`, image transform only | 60.990 ms | 39.016 ms | 72.226 ms | 39.734 ms | Channel arithmetic → lookup; per-pixel raster writes → direct output array; one 1,920-int working scanline |

The dashboard aggregate itself remains O(N) in qualifying database rows; the gain is bounded entity materialization, network payload, JSON parsing and elimination of N+1 queries. It does not promise constant database CPU as history grows. Full-list APIs retain their existing contracts and still return all rows; an API-versioned pagination design is a separate change.

Additional regression measurements (current-user lookup mocked/excluded in these focused repository tests):

- 24 knowledge topics with counts: 2 SQL statements
- 16 topics / 64 cards with reference counts: 2 SQL statements
- Knowledge search: 2 SQL statements; one-topic list: 3 SQL statements
- 16 workspaces / 48 members with permissions: 2 SQL statements, zero entity hydration
- 30-message inbox: 3 SQL statements, zero entity hydration; 501-message sent list: 5 statements
- 24-note list with share counts: 2 SQL statements

No timing speedup is claimed for the worker, transport reuse or these additional list paths until an end-to-end workload measures one. Batching removes 31 otherwise-required scheduling gaps per full 32-job burst; model inference/IO time remains unchanged.

## Actual MySQL + Redis HTTP check (2026-10-02)

The local runtime comparison used the guarded 500-task fixture in the same dedicated MySQL schema, with real Redis-backed rate limiting and ordinary authentication enabled. Each build received five warmup requests followed by 30 measured sequential requests, spaced 0.65 seconds apart, using the same HTTP/1.1 benchmark client.

| Endpoint | Original median | Optimized median | Original p95 | Optimized p95 |
| --- | ---: | ---: | ---: | ---: |
| `/api/v1/dashboard/summary`, 500 tasks | 104.391 ms | 50.541 ms | 257.405 ms | 78.746 ms |

Observed reductions: 51.6% median, 69.4% p95. Both runs returned HTTP 200 for all 30 samples, with no degraded rate-limit flag. Both reported 500 total / 167 completed / 167 failed tasks, 33.4% success and 0.15 confidence lift, and a median 49,357-byte response. The component equality test additionally checks the entire dashboard DTO. Local raw evidence is retained in `.runtime/evidence/http-before.json` and `http-after.json` (runtime files are intentionally not version controlled).

This is a small local sequential benchmark, not a production load/capacity test, and it does not measure real provider inference. It is distinct from the isolated Java/H2 timings above. Startup/readiness and component completeness are documented separately in the stack validation report.

## Run the component checks

```sh
mvn -B -Dtest=DashboardPerformanceTest,ImageEnhancementTest \
  -Dperformance.benchmark=true -Dperformance.rows=500 test
mvn -B -Dtest=InferenceQueueWorkerTest,ModelTransportTest,KnowledgeWorkspaceQueryIntegrationTest,MessageNoteBatchQueryTest test
mvn -B verify
```

Benchmark-only timing methods skip by default; correctness, exact pixels, query budgets, permission changes, FIFO/batch limits and transport credential/cleanup tests run normally. The model transport tests use a loopback HTTP stub and synthetic keys only. Full application security filters, real network TLS and real provider billing are outside those component tests.

## Compare live authenticated HTTP latency

Run `scripts/performance/api_benchmark.py` before and after on the same persistent database, dataset, account, JVM settings, hardware and service configuration. It keeps one HTTP/1.1 connection, excludes warmup samples, records raw samples/statuses/response sizes and reports median/nearest-rank p95. The default 0.65-second spacing respects the standard general 120-request/minute limit; it does not disable authentication, validation or rate limiting. A 429/error makes the benchmark fail, and a degraded rate-limit backend is recorded rather than silently ignored.

```sh
# Supply PERF_TOKEN or PERF_PASSWORD from your local secret environment.
# Do not put credentials in source files or command arguments.
python3 scripts/performance/api_benchmark.py --label before \
  --output .runtime/performance-before.json --samples 15 --warmup 3
# Rebuild/restart through your normal workflow, with the same database and settings.
python3 scripts/performance/api_benchmark.py --label after \
  --output .runtime/performance-after.json --samples 15 --warmup 3
```

For a concurrent run, add `--concurrency 4 --samples 30 --warmup 2`: this measures 30 total requests after 8 warmups, using one reused connection per worker. Requests launch in bounded waves; pacing is multiplied by wave size to preserve the aggregate rate budget. Compare the same concurrency level before and after. The harness was self-tested against a loopback HTTP/1.1 stub with 1 and 4 reused connections, and confirms 429 responses remain in results and cause a failed exit.

Default paths: dashboard, knowledge topics/cards, workspaces. Use repeated `--path` for a targeted comparison. Account for other users/processes consuming the same rate-limit budget. HTTP reports should include runtime versions, dataset cardinality, service modes, errors and startup/readiness separately; a faster empty dashboard is not evidence of historical-data scaling.

### Optional 500-task MySQL fixture

`scripts/performance/seed_mysql_dashboard.sql` is for a **dedicated disposable benchmark schema only**. It refuses schemas whose name does not start `rv_perf_`, refuses nonempty tasks/files, and requires already migrated/bootstrapped active admin + license-plate model rows. Both persistent inserts are also guarded, even if a caller incorrectly uses MySQL `--force`. It does not clear data or create users. Its 500 media records have **no object bytes** and must never be used as actual media samples.

```sh
mysql --batch --database=rv_perf_example < scripts/performance/seed_mysql_dashboard.sql
```

Expected result: 500 tasks/files, 167 completed, 167 failed, 166 pending, average confidence lift 0.15. Do not enqueue fixture tasks. Run original and optimized application builds against the same dedicated schema. Actual MySQL/HTTP measurements belong in the runtime validation report, separate from the H2 component measurements above.
