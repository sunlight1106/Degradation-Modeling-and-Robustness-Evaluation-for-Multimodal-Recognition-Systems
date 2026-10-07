> 默认 Docker Compose 用户请使用 [完整加密备份与恢复](../../docs/COMPOSE_BACKUP.md)。以下为旧的 MySQL + 本地文件系统演练工具。

# Local MySQL + filesystem recovery rehearsal

This is a **bounded local/development recovery tool**, not a production backup system. It supports Linux/POSIX, Python 3.10+, the official MySQL **8.4** `mysql`/`mysqldump` clients and an isolated MySQL 8.4 server. It never starts services, creates users/grants, changes server security settings, contacts remote storage, or drops a database.

## What is and is not backed up

A private ZIP contains:

- A complete application-schema SQL dump: tables, indexes, constraints, every row, and the original `flyway_schema_history`. Current schema: 34 application tables plus Flyway, V1–V18, 49 foreign keys.
- All regular files under the explicitly selected **filesystem storage root**, including files not referenced by current metadata. Each relative path has a size and SHA-256 manifest entry; every `file_asset.storage_path`, size and SHA must match the actual bytes.
- A table/engine/collation inventory, exact row counts, foreign-key inventory and migration versions/checksums.

Encrypted provider/personal-AI credentials remain encrypted in SQL. Password hashes, user content and other private database rows are present: **the ZIP itself is not encrypted**. Use a private encrypted disk and an independently protected backup destination; never commit, attach, publish or upload the resulting files with source releases. File paths/metadata are also private. Mode 600/700 protects ordinary local permissions, not other processes running as the same user.

The original `CREDENTIAL_MASTER_KEY` is required to decrypt restored provider credentials. Keep it, and required application/login configuration, separately in your existing approved secret-backup mechanism. Do not put `.env`, `.cnf`, `.key`, `.pem`, private keys or secret directories inside the selected storage root. The tool rejects these paths instead of silently omitting them. It does **not** copy environment variables, a client credentials file, key files or configuration into the ZIP. Retaining ciphertext alone does not recover a lost master key. A user's JSON export is a portability export, **not** a full database/files/secrets backup.

This format excludes MySQL accounts/grants, server configuration, Redis queue/AOF state, antivirus databases and MinIO/S3 objects. Do not point it at a MinIO data directory or describe a filesystem restore as a successful S3 restore. Non-InnoDB tables, non-utf8mb4 tables, views, routines, events, triggers and cross-schema foreign keys are rejected. Maximum payload: 512 MiB / 10,000 files; intended for small development datasets. Files restore as private regular files; filesystem ownership, timestamps, ACLs and empty directories are not preserved.

## Back up a quiesced local filesystem profile

1. Stop **all** API instances, workers, upload writers and schema/migration writers. Keep MySQL running. Preserve this write-free window through command completion. `--single-transaction` protects the SQL snapshot, but cannot synchronize an independently changing filesystem; inventory/hash rechecks are additional diagnostics, not a replacement for quiescing writers.
2. Confirm that the server is disposable/local. A loopback SSH tunnel can still reach production: **never use one**. The command requires an explicit `--local-dev` acknowledgment and only accepts literal `127.0.0.1` / `::1` in the client file.
3. Create a private backup directory outside your uploads root and source deliverables:

   ```sh
   umask 077
   mkdir -p .runtime/backups
   chmod 700 .runtime/backups
   # Use an editor/your approved secret manager to create this file; do not put passwords in shell arguments/history.
   "$EDITOR" .runtime/backups/client.cnf
   chmod 600 .runtime/backups/client.cnf
   ```

   Client-file shape (replace placeholders privately; no `!include` or other option groups):

   ```ini
   [client]
   host=127.0.0.1
   port=3306
   user="local_backup_account"
   password="your-existing-local-password"
   ```

   The backup account needs read access to the selected application tables and metadata. Restore needs permission to create/use its isolated `rv_restore_*` schema. The script does not provision privileges; use existing appropriately scoped local accounts. The checked-in integration test uses only a disposable CI server.

4. Use the actual filesystem `STORAGE_ROOT`, not the repository root or `.runtime` as a whole:

   ```sh
   python3 scripts/backup/local_backup.py --local-dev \
     --defaults-file "$PWD/.runtime/backups/client.cnf" \
     backup --writers-stopped --schema robust_vision \
     --files-root "$PWD/.runtime/uploads" \
     --output "$PWD/.runtime/backups/local-backup.zip"
   ```

   For portable clients outside `PATH`, put `--mysql /path/to/mysql --mysqldump /path/to/mysqldump` before `backup`/`restore`. Credentials are read by MySQL from the private file, never placed on the command line or printed. Ambient `MYSQL_*` variables and login-path files are ignored. MySQL diagnostics are deliberately suppressed because they can include SQL or row contents; the command fails nonzero rather than logging private data. Partial staging directories are cleaned; an existing output is never replaced.

## Rehearse an isolated restore

Only restore a **trusted archive created locally by this tool**. SQL is executable code. ZIP/path/SHA validation detects corruption and unsafe filesystem entries; SHA-256 is not authentication and cannot make a malicious SQL archive safe. Never download and restore someone else's bundle with an administrative account.

Choose a new schema named `rv_restore_` plus 8–40 lowercase letters, digits or underscores, and a nonexistent filesystem target under an existing mode-700 parent:

```sh
python3 scripts/backup/local_backup.py --local-dev \
  --defaults-file "$PWD/.runtime/backups/client.cnf" \
  restore --trusted-local-archive \
  --archive "$PWD/.runtime/backups/local-backup.zip" \
  --target-schema rv_restore_rehearsal_20261002 \
  --files-target "$PWD/.runtime/backups/restored-files"
```

Validation finishes before creating the database. `CREATE DATABASE` intentionally has no `IF NOT EXISTS`; existing schemas and existing filesystem targets are refused. Restoration rechecks the table/FK/Flyway inventories and row counts, re-dumps the whole database and compares its deterministic SQL SHA-256, verifies every file hash and its `file_asset` metadata, then writes the new filesystem target. Success prints only a small verified-count summary. No data or credential values are printed.

On failure the command exits nonzero. It never drops a database; a fresh partial restore schema may remain for inspection. Fix the cause and choose another **new** schema/target. Deleting a failed/successful rehearsal is a separate, intentional operator action after verifying ownership and the exact isolated name. Do not rename a restore over the live schema or switch the running app automatically. Before considering a real cutover, separately validate Flyway, application startup, authorization, health, content downloads, secret decryption and every required dependency; this tool performs no production cutover.

## Repeatable verification

Fast safety tests require no server:

```sh
python3 scripts/backup/test_local_backup.py
```

Real MySQL contract (only disposable loopback MySQL; existing URL-selected database is ignored):

```sh
export MYSQL_TEST_URL='jdbc:mysql://127.0.0.1:3306/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export MYSQL_TEST_USERNAME='existing_isolated_test_account'
# Supply MYSQL_TEST_PASSWORD via your existing private test environment.
export MYSQL_BACKUP_TESTS=true
# Optional: MYSQL_CLIENT_PATH=/path/to/mysql MYSQL_DUMP_PATH=/path/to/mysqldump
./mvnw -B -Dtest=MySqlBackupRestoreTest test
```

Without both enabling variables the test is **skipped**, not passed. Require `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`. The test creates fresh random source/restore schemas, runs real Flyway V1→latest, adds synthetic rows in every application domain, backs up and restores binary/Unicode files, checks exact SQL digest/row counts/40 FKs, Flyway validation and no-op migrate, FK/unique enforcement, Unicode notes and decimal precision. It encrypts a synthetic token with a synthetic master key, verifies ciphertext survived and decrypts with that same key; neither plaintext is packaged. It checks actual overwrite refusals. It drops only the source it created and the restore confirmed successfully created by this run; failed partial restores are not adopted/dropped. No app/customer data or real AI key is used. The temporary test archive and client file are removed on exit.

`.github/workflows/recovery.yml` repeats the fast and MySQL tests using a disposable MySQL 8.4 service and SHA-256-pinned official 8.4.6 clients. It publishes no database, backup, credentials or test runtime artifacts.

Official option references: [mysqldump](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html), [SQL dump database selection](https://dev.mysql.com/doc/refman/8.4/en/mysqldump-sql-format.html), [client option files](https://dev.mysql.com/doc/refman/8.4/en/option-file-options.html).

Personal AI memories are included in the SQL backup. Local PyTorch jobs and weights use the separate `training_data` volume, which this script does not capture. Back it up with the training service stopped and restore it alongside the matching user database; see [the training guide](../../docs/PERSONAL_AI.md#保存与迁移).
