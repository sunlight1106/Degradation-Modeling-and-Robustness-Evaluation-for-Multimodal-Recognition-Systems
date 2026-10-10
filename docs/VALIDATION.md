# 验证记录

## 2026-10-10：举报审核与处罚

- 完整后端回归：482 项，373 项执行通过，109 项按环境条件跳过，失败和错误为 0。最后补充同一消息重复举报和数字编号规范化防护，又通过 19 项举报回归与后端构建。
- 独立 MySQL 8.4：19 项举报、禁言、限时权限、封禁、申诉和跨用户访问检查通过；8 项新装、升级、并发迁移和旧举报证据保留检查通过。
- 前端 21 组交互回归与 5 项单元检查通过；举报组覆盖 11 项断言，包括原因必填、限制功能选择、时间换算、过期响应隔离和申诉。类型检查与生产构建通过。
- 自动处罚使用本地的有限规则，不发送真实 AI 请求。处罚不会改写原有角色或账号状态；到期也不会重新启用已停用或注销的账号。
- 更新后 8 个服务均健康，`check` 登录、审核队列、申诉筛选和本人处罚接口均返回 200。1440 × 1000 桌面浏览器检查了必填原因、功能多选、时长单位、本人记录和封禁后的申诉入口；没有横向溢出或 JavaScript 错误。表单演示使用浏览器中的临时数据，没有处罚真实用户。

后续管理员功能见 [管理员使用与权限说明](ADMINISTRATION.md)，搜索、收藏和常用搜索见 [学习中心使用指南](LEARNING_WORKSPACE.md)。本文下面保留的是 2026-10-02 当时的检查记录，其中部署限制不代表当前版本；新的搜索性能记录在 [性能说明](PERFORMANCE.md)。

## 2026-10-10：搜索与整理

- 后端构建：339 项测试，284 项执行通过，55 项按条件跳过，失败和错误为 0。
- 真实 MySQL 8.4：12 项搜索、隐私、收藏去重与数量限制检查通过；7 项新装、旧版升级和并发迁移检查通过。最终的来源校验与收藏清理又复验了 2 项。
- 前端：17 组交互回归通过，最新搜索组包含 14 项断言；类型检查和生产构建通过。
- 本地登录、搜索、收藏、常用搜索保存与更新通过。1440 × 1000 桌面浏览器检查了键盘选择、原文打开、保存后收起、亮暗主题；页面没有横向溢出，最终页面控制台没有错误或警告。
- 更新后 8 个 Compose 服务均健康。临时验收笔记、收藏、搜索和登录会话已清理。

## 2026-10-10：群会话和学习助手

- 完整后端构建：350 项测试，287 项执行通过，63 项按环境条件跳过，失败和错误为 0。
- 独立 MySQL 8.4：群组通信 8 项、数据库迁移 7 项、个人 AI 用量持久化 6 项、词汇与导出 24 项通过。覆盖公告版本冲突、跨群消息限制、退出后的权限撤回、个人会话偏好和旧数据升级。
- 前端 18 组交互回归通过；最后的公告并发编辑修正又通过群组 35 项断言。单词辅助 16 项、笔记与个人配置 57 项及请求保护 3 项通过。类型检查和生产构建通过。
- 在运行中的服务上验证了公告、置顶消息、会话偏好、已读、消息搜索和成员搜索。1440 × 1000 桌面检查了群组、单词助手与笔记入口；长群名没有横向溢出，最终浏览器控制台错误与警告为 0。
- AI 发送确认、权限收回和结果保存使用模拟响应验证，没有发送真实付费模型请求。当前验收账号没有启用个人模型，远程动作需先配置模型才能使用。
- 8 个服务均健康，首页和登录返回 200。仅清理本次临时群组与验收会话，保留原有数据。

## Implementation validation, 2026-10-02

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


## Follow-on note-reference optimization (base 1e9eaf9)

- Production scope: bounded scalar reference/target queries for note detail and shared-note reads. No schema, API or permission changes.
- Final Maven verify in an isolated source copy: 49 tests, 43 passed, zero failures/errors, six intentional skips. The live runtime JAR was not overwritten.
- Actual MySQL 8.4.6: all eight `NoteReferenceBatchQueryTest` methods passed, no skips. Coverage includes exact baseline DTO equality, missing/deleted targets, labels/order, 501-reference boundary, fresh state, note ownership, same-transaction add/remove responses, share-token revocation/expiry, and case-insensitive UUID matching.
- 500-reference service/JDBC benchmark: 667 → 4 SQL statements, 1,333 → 0 entity loads; median 104.116 → 20.976 ms, p95 191.450 → 33.839 ms. Five warmups and 15 alternating samples; full results must match. These are not HTTP or production-throughput numbers.
- Independent static review found no actionable defect. The MySQL CI job now includes the new correctness suite; timing remains opt-in.
- No deployment, runtime restart, new credentials or destructive application-data migration was performed. Earlier component/runtime caveats remain applicable.

Method and reproduction commands: [PERFORMANCE.md](PERFORMANCE.md#follow-on-note-reference-batch-actual-mysql-2026-10-02).

## Private AI / personal settings / vocabulary feature verification (2026-10-02)

New application code was validated separately from the retained historical runtime; it was not deployed over existing user data.

- Independent published security batches: ownership/knowledge `87f31db`, request/proxy/logging `8b0f619`, and upload active-content inspection `38ec65c`. Their frontend/backend/MySQL CI jobs passed.
- Full isolated source build: 211 tests, zero failures/errors, 21 expected skips (external-MySQL cases and optional timing checks). All 190 non-skipped cases executed successfully; actual database validation is listed separately below.
- Real disposable MySQL 8.4.6: 38 cases, zero failures/errors, one opt-in timing skip. Coverage includes vocabulary 15, administrator concurrency 5, migration/upgrade preservation 3, accounting concurrency 4, reference correctness 9 executed cases, and the actual 71-request HTTP workflow. Fresh V1–V12 and populated V6/V7 upgrades validate all 31 tables, 40 foreign keys and 29 Hibernate entities; prior data and migration checksums are preserved.
- The real-engine run found a repeatable-read race that the H2 vocabulary test had not exposed. It was repaired using explicit transaction isolation and locked question/progress reads; the same concurrent replay/next tests then passed against MySQL.
- Personal AI adapters and image recognition use synthetic keys and mocked upstreams. The actual random-port servlet HTTP test made 71 real requests, with exactly three synthetic provider completions and an explicitly mocked antivirus boundary. Verification covers native Anthropic/Gemini versus OpenAI-compatible protocols, private ownership, preview/execute binding, replay, config changes, rate/concurrency bounds, secret redaction, nullable/failed-call usage, SSRF/DNS pinning and output limits. No real provider account, model availability or billing was validated.
- Frontend synthetic DOM suite: 38 assertions plus 3 request-guard unit tests, TypeScript and production build passed. Tests include two-account cross-tab changes, stale responses, no-send cancellation, one-shot consent, private settings and all-time versus recent usage. Full visual browser inspection remains unavailable because the cloud browser blocks local application URLs. No forwarding bypass was attempted.
- Nginx syntax and an actual synthetic private-URL logging regression are now included in CI using the same official image family as the frontend. Local Docker/Compose execution remains unverified.
- A concurrent validation attempt encountered environment stalls and one transient MySQL communication failure. No failed/interrupted run was counted as a pass; the final serial, heap-bounded MySQL run above completed successfully.

The freshly built production JAR was also launched against a separate disposable MySQL 8.4.6 schema for 51 real HTTP requests. It applied 12 migrations, verified owner settings/encrypted storage, notes, vocabulary four-answer mastery/next-day review, session logout replay, and accurate SKIPPED scan handling. Remote execution stayed disabled by the shipped default; no provider call was made. Rate limiting stayed enabled, using its bounded fallback when Redis was absent. No antivirus mock was installed in that production-JAR run: disabled antivirus remained SKIPPED and recognition was denied. The JAR stopped cleanly and its owned temporary database was dropped. JAR SHA-256: `2b96de75b46f27fb2a4f823091dad09b1a31cc7dc017ac3beab0128f1b623504`.

Reproduction: `PersonalPlatformHttpIntegrationTest` (actual HTTP, explicit synthetic provider/AV boundary; H2 or opt-in MySQL), `scripts/tests/private-platform-http-smoke.py` (fresh production JAR, default external-execution denial, guarded disposable MySQL). Production rollout, TLS/MinIO validation and real personal-provider calls remain outside the completed local/mocked checks.

The final long-note regression verifies that local assistance rejects oversized content without silently truncating it: the actual HTTP test saves a body over 24,000 characters, receives an explicit error, then reads back the identical original body. DOM tests verify no replacement result can be applied and that pending explicit saves block navigation; an extra response-generation check discards obsolete saves. No notebook autosave or real-time multi-editor merge is claimed.
