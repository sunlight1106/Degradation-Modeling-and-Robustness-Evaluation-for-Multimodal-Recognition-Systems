# Database / 数据库

## Authoritative schema

`migrations/` is the only schema source. Maven packages it as `db/migration` in the backend jar; Flyway applies versions in order, and production Hibernate uses `ddl-auto=validate` (never `update` or `create`). MySQL **8.4**, InnoDB and `utf8mb4` are the supported production configuration.

| Version | Scope |
| --- | --- |
| V1 | Accounts, RBAC, media assets, model catalog, inference tasks |
| V2 | Legacy DeepSeek model-catalog upgrade |
| V3 | Scan status, queue-related task metadata, token/cost accounting, wallets and provider budgets |
| V4 | Legacy Qwen video-model catalog upgrade |
| V5 | Payment sandbox, workspaces, messages, encrypted provider credentials |
| V6 | Knowledge topics/cards, notes, references and share tokens |
| V7 | Composite indexes matching existing repository filters/orderings |
| V8 | Owner-scoped encrypted personal AI settings and nullable/known usage metadata |
| V9 | Revocable, expiring login sessions |
| V10 | Vocabulary books, words, per-user settings/progress and one-use question records |
| V11 | Original starter vocabulary (no third-party proprietary corpus) |
| V12 | Owner-scoped personal image recognition results |
| V19 | Permanent unique user identity codes; existing-user backfill |
| V20 | Per-user private contact remarks, pins, mute settings and chat read/clear cursors |

V1–V6 remain unchanged. V7 adds indexes only: it does not replace tables, change primary keys, reset passwords, rewrite content or modify balances. MySQL `CHAR` columns are explicitly mapped as `CHAR` in Hibernate, including fixed-width IDs, hashes and tokens; changing deployed IDs to `VARCHAR` is unnecessary.

### Complete persisted domain

There are **31 application tables**, **29 JPA entities**, two permission collection tables and **40 foreign keys** (plus Flyway's history table):

| Domain | Tables | Integrity / access paths |
| --- | --- | --- |
| Identity / permissions | `app_user`, `app_role`, `role_permission` | Unique identity code/username/email/role code; role FK; unique role-permission pair |
| Media / inference | `file_asset`, `model_definition`, `inference_task` | File/model/user FKs; unique trace ID and model code/version; owner and global recent-item indexes |
| Billing sandbox | `user_wallet`, `wallet_ledger`, `recharge_order`, `provider_budget` | One wallet per user; exact decimal amounts; unique nullable payment-token hash; user/timestamp indexes |
| Collaboration | `workspace`, `workspace_member`, `workspace_member_permission`, `internal_message`, `message_recipient`, `message_attachment` | Unique slug/member/recipient/attachment pairs; FK cascades for dependent rows; membership chronological indexes |
| Provider credentials | `provider_credential` | Creator FK; encrypted secret only; active/provider/created-order indexes |
| Knowledge | `knowledge_topic`, `knowledge_entry` | Topic/user FKs; unique user topic name; owner/topic ordered-list indexes |
| Personal AI / recognition | `personal_ai_setting`, `personal_ai_usage`, `personal_recognition_result` | Per-owner profiles, no plaintext keys; private usage/results; unknown token usage remains NULL |
| Login sessions | `user_session` | Owner FK, expiry/revocation, server-verified JWT session ID |
| Vocabulary | `vocabulary_book`, `vocabulary_word`, `vocabulary_profile`, `vocabulary_progress`, `vocabulary_question` | Private books/progress, unique owner-word progress, one-use answer accounting |
| Notes / sharing | `note`, `note_reference`, `note_share` | Note/creator FKs; unique note-target pair and share token; owner/status/update and reference order indexes |

Redis queue entries and MinIO object bytes are not relational tables. Dataset ZIP/JSONL exports are derived from existing tasks/media; no fake training-job or checkpoint tables are added. The production repository layer uses Spring Data JPA and JPQL, with one native MySQL/H2-compatible insert-if-absent statement for atomic provider-budget initialization (see concurrency below).

### Deliberate limits

- `note_reference` is polymorphic (`FILE`, `TASK`, `ENTRY`). Its note has a FK; the target ID is checked by `NoteReferenceService`, because one column cannot reference three tables. Use the read-only diagnostics below to detect stale targets.
- MySQL permits repeated `NULL` values in a unique index. The V6 topic-name constraint prevents duplicate user-owned topics, but does **not** independently guarantee unique ownerless built-in topics. Bootstrap is enabled on the API and disabled on the worker by Compose. Multiple simultaneous bootstrap-enabled API instances require serialized initialization before scaling out; V7 does not silently delete existing duplicate data.
- Leading-wildcard text search (`LIKE '%term%'`) remains a scan within the permitted owner/topic scope. The added B-tree indexes do not claim to make arbitrary substring search indexed.
- Indexes are not a substitute for bounded queries and pagination. A small fixture verifies schema/access-path presence, not production throughput.

## Verification against real MySQL

The default fast tests use H2 and do **not** validate MySQL DDL. `MySqlSchemaMigrationTest` is a separate opt-in contract that uses the actual Flyway SQL and real MySQL 8.4. No Docker or Testcontainers dependency is required if an isolated server is already running.

Provide a disposable local/CI MySQL server. Its test account needs privileges only on database names matching the escaped `rv\_schema\_test\_%` pattern. The tests create randomly named schemas, do not adopt an existing schema, and drop only schemas they created. The database named in the URL is never migrated or cleaned. Do not use a production server/account.

```sh
export MYSQL_TEST_URL='jdbc:mysql://127.0.0.1:3306/?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false'
export MYSQL_TEST_USERNAME='local_test_user'
# Supply MYSQL_TEST_PASSWORD through your local secret/environment mechanism.
./mvnw -Dtest=MySqlSchemaMigrationTest test
```

Without `MYSQL_TEST_URL`, the five schema contract cases are reported **skipped**, not passed. In CI or release verification, explicitly provide these variables and check `target/surefire-reports/com.robustvision.platform.database.MySqlSchemaMigrationTest.txt` for `Tests run: 5`, `Failures: 0`, `Errors: 0`, `Skipped: 0`.

The tests verify:

1. Fresh V1 → latest installation on MySQL 8.4, all application tables, InnoDB/utf8mb4, all 29 entity mappings via Hibernate schema validation.
2. Populated V6 and V7 → latest upgrades with synthetic rows in every table, checking every domain row and all previously applied Flyway checksums are unchanged.
3. Repeated migrate is a no-op; Flyway validation succeeds.
4. Referential and unique constraints, representative note-child cascades, Unicode/emoji round trips and exact money/cost precision.
5. Column order of the query indexes used by owner-scoped and global lists.

Run `./mvnw verify` too. The schema contract does not replace API authorization, queue/worker, payment sandbox, object storage or end-to-end tests.

## Concurrent sandbox accounting

Wallet mutations use database row locks. Existing accounts lock only their own wallet. When a wallet is absent, the service first locks the existing user row, then repeats the wallet lookup with a locking current read; this prevents both duplicate creation and stale MySQL `REPEATABLE READ` snapshots. Locks are acquired in the order recharge order (if applicable), user (first creation only), wallet, provider budget. No process-local/global mutex is used.

Provider-budget creation uses `INSERT ... ON DUPLICATE KEY UPDATE` as a row-local no-op on an existing record, followed by a locked lookup. It does not reset configured budgets or usage. Charges to the same provider serialize briefly on that provider's accounting row; different provider rows are independent. Both wallet/ledger and provider usage changes commit in the surrounding transaction.

Read-only provider/admin-wallet listings do not write back stale balances, create every missing wallet, or hold write locks during remote balance requests. Provider views display zero usage for a previous month's row; the next locked usage write performs the actual rollover. Recharge reads that may expire an order lock that order so they cannot overwrite a concurrent settlement.

`BillingConcurrencyIntegrationTest` runs on isolated H2 by default. With the same `MYSQL_TEST_*` environment it creates its own disposable MySQL schema, applies the real migrations and runs the same concurrent service/authorization tests:

```sh
./mvnw -Dtest=BillingConcurrencyIntegrationTest,MySqlSchemaMigrationTest test
```

Coverage includes simultaneous first-wallet creation, different orders crediting one wallet, repeated-payment rejection, concurrent administrator adjustments, rejected non-administrator changes, and charges to multiple wallets sharing a lazily initialized provider budget. These are local payment-sandbox accounting tests; no real payment service is contacted. Locks prevent lost updates but do not reserve money for in-flight model requests or make the Redis job queue exactly-once.

## Read-only diagnostics

Run `diagnostics.sql` with the MySQL client against the intended database after selecting it. It reports migration state, actual table engines/collations, FK/index coverage, duplicate public topics and dangling polymorphic note references. It makes **no changes** and does not display encrypted provider secrets or user password hashes.

```sh
# Authentication belongs in the client's safe password prompt or environment.
mysql --host=127.0.0.1 --user=robust_user --password robust_vision < database/diagnostics.sql
```

## Upgrade / recovery / backup

1. Capture coordinated MySQL/object-storage backups and separately protect the original application/encryption secrets. Never package `.env` or private keys with source releases or ordinary backup archives. Encrypted provider credentials require the original `CREDENTIAL_MASTER_KEY`. A user JSON export is not a full backup. Test restores on an isolated server. For the bounded local filesystem profile, see the [executable backup and isolated restore rehearsal](../scripts/backup/README.md). MinIO/S3 recovery needs its own separately verified procedure.
2. Stop or quiesce old application writers before upgrading. The API and worker may initialize together: both run Flyway, which serializes migration through its database lock, followed by Hibernate schema validation. Only the API bootstraps seed records. Do not disable worker migration when starting it concurrently. Fresh production schemas should be created as `CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`.
3. Verify Flyway success and `ddl-auto=validate`, then check health and exercise login, note creation and an inference task before restoring ordinary traffic.
4. Do not edit an applied migration or use `baseline-on-migrate` to hide a drifted schema. Add the next version for future changes.
5. MySQL DDL is not fully transactional. A failed index migration can leave earlier indexes committed. Inspect both `flyway_schema_history` and `SHOW INDEX` before recovery. Never blindly run `repair`, replay the entire migration or delete history. Reconcile the precise partial changes with a verified backup and an explicit maintenance plan first.

Runtime MySQL, Redis and object-storage data belongs in Docker named volumes, not Git. `docker compose down` keeps volumes; `docker compose down -v` destroys them. Private runtime logs and local credentials must stay outside commits.

## User identity codes

V19 assigns every existing account a `PKB-` code without changing its internal ID, password, relationships or profile timestamp. New accounts receive a random UUID-based code automatically. The database requires non-null, unique codes; APIs expose them as `identityCode` but do not accept edits to them. Exact code lookup follows the same discovery, disabled-account and blocking rules as user search. Keep the database volume or restore its backup when moving deployments to retain existing codes.

Contact preferences in V20 are independent for each side of a contact pair and updated under the existing pair lock. Clearing history advances only the acting user's cursor; all message pagination paths honor it and the peer's history is retained. Mute hides unread badges, not delivery. Blocking preserves accepted relationships but never accepts a pending request, and either side's block still denies discovery, direct mail and chat.
## 收藏与常用搜索

V26 新增 `workspace_shortcut`，保存每个账号的资料收藏标识和常用搜索条件；`owner_id` 外键随账号删除，`(owner_id,kind,resource_key)` 唯一约束防止重复保存。升级由 Flyway 自动执行，不需要手工导入。完整 Docker 数据备份包含该表。

## 举报审核

V33 新增 `moderation_report`、`moderation_penalty` 和 `moderation_event`。消息证据快照、限时处罚、申诉和处理记录一起保存在 MySQL。旧群组举报会迁入统一审核队列；证据不会因为原消息撤回或群组解散而丢失。处罚以截止时间和解除时间判断有效性，不改写原有角色或账号状态。操作步骤见 [举报与处罚](../docs/MODERATION.md)。
