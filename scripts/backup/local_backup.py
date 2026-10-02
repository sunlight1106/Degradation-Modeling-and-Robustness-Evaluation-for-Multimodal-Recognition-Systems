#!/usr/bin/env python3
"""Bounded local/development MySQL 8.4 + filesystem backup and restore rehearsal.

Never use a production/forwarded connection or an untrusted archive. See README.md.
Only Python's standard library and official MySQL 8.4 clients are required.
"""
import argparse
import configparser
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile

FORMAT = "personal-platform-local-backup-v1"
MAX_BYTES = 512 * 1024 * 1024
MAX_ENTRIES = 10000
IDENTIFIER = re.compile(r"[A-Za-z][A-Za-z0-9_]{0,63}\Z")
RESTORE_NAME = re.compile(r"rv_restore_[a-z0-9_]{8,40}\Z")


class BackupError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise BackupError(message)


def identifier(name):
    require(isinstance(name, str) and IDENTIFIER.fullmatch(name), "Unsafe SQL identifier")
    require(name.lower() not in {"mysql", "sys", "performance_schema", "information_schema"},
            "System schemas cannot be backed up or restored")
    return "`" + name + "`"


def safe_relative(name):
    require(isinstance(name, str) and name and "\\" not in name and "\x00" not in name,
            "Unsafe archive/file path")
    parts = name.split("/")
    require(not PurePosixPath(name).is_absolute() and all(p not in {"", ".", ".."} for p in parts),
            "Unsafe archive/file path")
    require(all(not any(ord(c) < 32 or ord(c) == 127 for c in p) for p in parts), "Control character in path")
    require(not any(p.lower().startswith(".env") or p.lower() in {".git", ".ssh", ".aws", "secrets"}
                    or p.lower().endswith((".key", ".pem", ".cnf", ".p12", ".pfx")) for p in parts),
            "Secret/configuration paths must not be packaged")
    return name


def private_directory(path):
    path = Path(path).absolute()
    require(path.is_dir() and not path.is_symlink(), "Private parent directory must already exist")
    require(path.resolve() == path, "Symlinked directory paths are refused")
    require(stat.S_IMODE(path.stat().st_mode) & 0o077 == 0, "Parent directory must have mode 700")
    return path


def private_file(path):
    path = Path(path).absolute()
    require(path.is_file() and not path.is_symlink() and path.resolve() == path,
            "Expected a regular, non-symlink private file")
    require(stat.S_IMODE(path.stat().st_mode) & 0o077 == 0, "Private file must have mode 600")
    require(path.stat().st_uid == os.getuid(), "Private file must belong to the current user")
    return path


def digest(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def file_manifest(root):
    root = Path(root).absolute()
    require(root.is_dir() and root.resolve() == root and not root.is_symlink(),
            "Filesystem root must be a non-symlink directory")
    result = {}
    total = 0
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in dirs + files:
            path = Path(directory) / name
            relative = safe_relative(path.relative_to(root).as_posix())
            require(not path.is_symlink(), "Filesystem symlinks are refused")
            if name in dirs:
                require(path.is_dir(), "Unsupported filesystem entry")
                continue
            require(path.is_file(), "Only regular filesystem files are supported")
            size = path.stat().st_size
            total += size
            require(total <= MAX_BYTES and len(result) < MAX_ENTRIES, "Local backup size/count limit exceeded")
            result[relative] = {"size": size, "sha256": digest(path)}
    return dict(sorted(result.items()))


class MySQL:
    def __init__(self, defaults_file, mysql="mysql", mysqldump="mysqldump"):
        self.defaults_file = private_file(defaults_file)
        require(self.defaults_file.stat().st_size <= 16384, "Client configuration is too large")
        # Disallow includes, arbitrary client commands, alternate sections and remote endpoints.
        # Password contents are never copied into arguments, the bundle, or diagnostic output.
        config = configparser.ConfigParser(interpolation=None, inline_comment_prefixes=None)
        try:
            config.read_string(self.defaults_file.read_text())
        except (configparser.Error, UnicodeError):
            raise BackupError("Invalid private client configuration") from None
        require(config.sections() == ["client"] and not config.defaults(), "Only a [client] section is allowed")
        require(set(config["client"]) <= {"host", "port", "user", "password"}, "Unsupported client configuration option")
        self.host = config["client"].get("host", "")
        self.port = config["client"].get("port", "3306")
        require(self.host in {"127.0.0.1", "::1"}, "Only explicit loopback hosts are allowed")
        require(self.port.isdecimal() and 1 <= int(self.port) <= 65535, "Invalid local MySQL port")
        require(bool(config["client"].get("user")), "Client user is required")
        self.mysql, self.mysqldump = mysql, mysqldump
        self.env = {k: v for k, v in os.environ.items() if not k.startswith("MYSQL")}
        for program in (mysql, mysqldump):
            version = self.run([program, "--no-defaults", "--no-login-paths", "--version"])
            require(re.search(rb"(?:Ver|Distrib) 8\.4\.", version), "Official MySQL 8.4 clients are required")
        require(self.query("SELECT VERSION()").startswith("8.4."), "MySQL 8.4 server is required")

    def run(self, args, *, content=None, source=None, destination=None):
        try:
            result = subprocess.run(args, input=content, stdin=source, stdout=destination or subprocess.PIPE,
                                    stderr=subprocess.PIPE, env=self.env, timeout=180, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise BackupError("MySQL client could not run or timed out; no credentials/SQL are logged") from None
        require(result.returncode == 0, "MySQL command failed; private SQL/client diagnostics are deliberately suppressed")
        return result.stdout

    def options(self, program):
        return [program, "--defaults-file=" + str(self.defaults_file), "--no-login-paths",
                "--protocol=TCP", "--host=" + self.host, "--port=" + self.port,
                "--default-character-set=utf8mb4"] + (["--connect-timeout=10"] if program == self.mysql else [])

    def query(self, sql, schema=None):
        args = self.options(self.mysql) + ["--batch", "--raw", "--skip-column-names", "--binary-mode=1"]
        if schema:
            identifier(schema)
            args.append("--database=" + schema)
        return self.run(args, content=(sql + ";\n").encode()).decode().strip()

    def json_rows(self, sql, schema):
        return [json.loads(line) for line in self.query(sql, schema).splitlines() if line]

    def dump(self, schema, output):
        identifier(schema)
        args = self.options(self.mysqldump) + [
            "--single-transaction", "--quick", "--no-tablespaces", "--set-gtid-purged=OFF",
            "--skip-comments", "--skip-dump-date", "--skip-add-drop-table", "--skip-add-locks",
            "--skip-disable-keys", "--skip-extended-insert", "--skip-triggers", "--hex-blob",
            "--complete-insert", "--order-by-primary", schema]
        # No pipeline: a failed dump never becomes a successful empty gzip/archive.
        with Path(output).open("xb") as stream:
            self.run(args, destination=stream)
        require(0 < Path(output).stat().st_size <= MAX_BYTES, "SQL dump exceeds the bounded local-backup limit")

    def snapshot(self, schema):
        identifier(schema)
        tables = self.json_rows("SELECT JSON_OBJECT('name', TABLE_NAME, 'engine', ENGINE, 'collation', TABLE_COLLATION, 'type', TABLE_TYPE) FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY TABLE_NAME", schema)
        require(tables and all(t["type"] == "BASE TABLE" and t["engine"] == "InnoDB"
                              and (t["collation"] or "").startswith("utf8mb4") for t in tables),
                "Only InnoDB/utf8mb4 table-only application schemas are supported")
        names = [t["name"] for t in tables]
        require("flyway_schema_history" in names and "file_asset" in names, "Expected an application schema with Flyway and file_asset")
        for kind in ("routines", "triggers", "events"):
            column = {"routines": "routine_schema", "triggers": "trigger_schema", "events": "event_schema"}[kind]
            require(self.query(f"SELECT COUNT(*) FROM information_schema.{kind} WHERE {column}=DATABASE()", schema) == "0",
                    "Stored routines, triggers and events are outside this local backup format")
        require(self.query("SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND referenced_table_schema IS NOT NULL AND referenced_table_schema<>DATABASE()", schema) == "0", "Cross-schema foreign keys are unsupported")
        counts = {name: int(self.query("SELECT COUNT(*) FROM " + identifier(name), schema)) for name in names}
        foreign_keys = self.json_rows("SELECT JSON_OBJECT('table', TABLE_NAME, 'name', CONSTRAINT_NAME, 'column', COLUMN_NAME, 'position', ORDINAL_POSITION, 'parent', REFERENCED_TABLE_NAME, 'parent_column', REFERENCED_COLUMN_NAME) FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND referenced_table_name IS NOT NULL ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION", schema)
        migrations = self.json_rows("SELECT JSON_OBJECT('version', version, 'checksum', checksum, 'success', success) FROM flyway_schema_history ORDER BY installed_rank", schema)
        require(migrations and all(m["success"] == 1 for m in migrations), "Flyway history is empty or has failed migrations")
        return {"tables": tables, "row_counts": counts, "foreign_keys": foreign_keys, "migrations": migrations}

    def check_assets(self, schema, files):
        assets = self.json_rows("SELECT JSON_OBJECT('path', storage_path, 'size', size_bytes, 'sha256', sha256) FROM file_asset ORDER BY id", schema)
        for asset in assets:
            path = safe_relative(asset["path"])
            require(files.get(path) == {"size": asset["size"], "sha256": asset["sha256"]},
                    "A file_asset has missing/different filesystem bytes; backup/restore refused")
        return len(assets)


def backup(db, schema, files_root, output):
    identifier(schema)
    output = Path(output).absolute()
    parent = private_directory(output.parent)
    require(not output.exists() and not output.is_symlink(), "Backup destination already exists")
    root = Path(files_root).absolute()
    require(not parent.is_relative_to(root), "Backup output must be outside the filesystem source")
    files = file_manifest(root)
    before = db.snapshot(schema)
    assets = db.check_assets(schema, files)
    with tempfile.TemporaryDirectory(prefix=".backup-", dir=parent) as temporary:
        temporary = Path(temporary)
        sql = temporary / "database.sql"
        db.dump(schema, sql)
        require(db.snapshot(schema) == before, "Database changed during backup; stop all writers and retry")
        require(file_manifest(root) == files, "Files changed during backup; stop all writers and retry")
        manifest = {"format": FORMAT, "source_schema": schema, "storage": "filesystem", "database": before,
                    "sql": {"size": sql.stat().st_size, "sha256": digest(sql)}, "files": files,
                    "asset_count": assets}
        require(manifest["sql"]["size"] + sum(f["size"] for f in files.values()) <= MAX_BYTES, "Combined backup size limit exceeded")
        archive = temporary / "bundle.zip"
        with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED) as bundle:
            bundle.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, sort_keys=True).encode())
            bundle.write(sql, "database.sql")
            for relative in files:
                bundle.write(root / relative, "files/" + relative)
        # Validate bytes actually packaged, not just the earlier source hashes.
        with tempfile.TemporaryDirectory(prefix=".check-", dir=temporary) as check:
            unpack(archive, Path(check))
        require(file_manifest(root) == files and db.snapshot(schema) == before,
                "Source changed while packaging; stop all writers and retry")
        os.chmod(archive, 0o600)
        os.link(archive, output)  # Atomic publication; never overwrite a concurrent existing file.
    return {"result": "backup_verified", "tables": len(before["tables"]), "files": len(files), "assets": assets}


def unpack(archive, staging):
    """Validate every member before writing; never use ZipFile.extract/extractall."""
    archive = private_file(archive)
    require(archive.stat().st_size <= MAX_BYTES, "Archive exceeds the local size limit")
    with zipfile.ZipFile(archive) as bundle:
        entries = bundle.infolist()
        names = [entry.filename for entry in entries]
        require(len(entries) <= MAX_ENTRIES + 2 and len(names) == len(set(names)), "Duplicate/too many archive entries")
        require(sum(entry.file_size for entry in entries) <= MAX_BYTES + 4 * 1024 * 1024, "Expanded archive exceeds local limit")
        for entry in entries:
            safe_relative(entry.filename)
            kind = stat.S_IFMT(entry.external_attr >> 16)
            require(kind in {0, stat.S_IFREG} and not entry.is_dir() and not entry.flag_bits & 1,
                    "Archive links/directories/special/encrypted entries are refused")
        require("manifest.json" in names and "database.sql" in names, "Missing backup manifest or database")
        require(bundle.getinfo("manifest.json").file_size <= 4 * 1024 * 1024, "Manifest too large")
        manifest = json.loads(bundle.read("manifest.json"))
        require(manifest.get("format") == FORMAT and manifest.get("storage") == "filesystem", "Unsupported backup format")
        identifier(manifest["source_schema"])
        files = manifest["files"]
        require(isinstance(files, dict), "Invalid file manifest")
        expected = {"manifest.json", "database.sql"} | {"files/" + safe_relative(name) for name in files}
        require(set(names) == expected, "Unlisted/missing archive member")
        for name, record in {"database.sql": manifest["sql"], **{"files/" + key: value for key, value in files.items()}}.items():
            require(isinstance(record, dict) and type(record.get("size")) is int and record["size"] >= 0
                    and re.fullmatch(r"[a-f0-9]{64}", record.get("sha256", "")), "Invalid checksum record")
            require(bundle.getinfo(name).file_size == record["size"], "Archive size mismatch")
            target = staging / name
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            with bundle.open(name) as source, target.open("xb") as destination:
                shutil.copyfileobj(source, destination, 1024 * 1024)
            require(digest(target) == record["sha256"], "Archive SHA-256 mismatch")
        (staging / "files").mkdir(exist_ok=True, mode=0o700)
    return manifest


def restore(db, archive, schema, files_target):
    require(RESTORE_NAME.fullmatch(schema), "Restore schema must be rv_restore_ followed by 8–40 lowercase letters/digits/underscores")
    identifier(schema)
    target = Path(files_target).absolute()
    parent = private_directory(target.parent)
    require(not target.exists() and not target.is_symlink(), "Restore filesystem target already exists")
    require(db.query("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + schema + "'") == "0",
            "Restore database already exists; it will not be changed")
    # Validation precedes CREATE DATABASE. Only this freshly created schema receives SQL.
    with tempfile.TemporaryDirectory(prefix=".restore-", dir=parent) as temporary:
        staging = Path(temporary)
        manifest = unpack(archive, staging)
        require(schema != manifest["source_schema"], "Source and restore schema must differ")
        db.query("CREATE DATABASE " + identifier(schema) + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
        # Never automatically DROP, even on failure. Partial fresh schemas are left for inspection.
        with (staging / "database.sql").open("rb") as source:
            db.run(db.options(db.mysql) + ["--binary-mode=1", "--batch", "--database=" + schema], source=source)
        require(db.snapshot(schema) == manifest["database"], "Restored table/count/FK/Flyway inventory differs")
        db.check_assets(schema, manifest["files"])
        restored_sql = staging / "redump.sql"
        db.dump(schema, restored_sql)
        require(digest(restored_sql) == manifest["sql"]["sha256"], "Restored SQL content/schema digest differs")
        require(file_manifest(staging / "files") == manifest["files"], "Restored file content differs")
        # Exclusive creation preserves any existing/concurrently created destination.
        target.mkdir(mode=0o700)
        try:
            for relative in manifest["files"]:
                destination = target / relative
                destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                with (staging / "files" / relative).open("rb") as source, destination.open("xb") as stream:
                    shutil.copyfileobj(source, stream)
            require(file_manifest(target) == manifest["files"], "Published filesystem verification failed")
        except BaseException:
            # Only our exclusive-created target is removed; no pre-existing files are touched.
            shutil.rmtree(target)
            raise
    return {"result": "restore_verified", "schema": schema, "tables": len(manifest["database"]["tables"]),
            "files": len(manifest["files"]), "assets": manifest["asset_count"], "sql_sha256_match": True}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local-dev", required=True, action="store_true", help="Acknowledge this is a disposable/local server, never production or a tunnel")
    parser.add_argument("--defaults-file", required=True, help="Private mode-600 MySQL [client] file; never pass a password on the command line")
    parser.add_argument("--mysql", default="mysql")
    parser.add_argument("--mysqldump", default="mysqldump")
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("backup")
    create.add_argument("--writers-stopped", required=True, action="store_true", help="API, workers, uploads and DDL writers are stopped for the entire backup")
    create.add_argument("--schema", required=True)
    create.add_argument("--files-root", required=True, type=Path)
    create.add_argument("--output", required=True, type=Path)
    load = commands.add_parser("restore")
    load.add_argument("--trusted-local-archive", required=True, action="store_true", help="Acknowledge SQL archive is locally created and trusted; SHA-256 is not authentication")
    load.add_argument("--archive", required=True, type=Path)
    load.add_argument("--target-schema", required=True)
    load.add_argument("--files-target", required=True, type=Path)
    args = parser.parse_args(argv)
    os.umask(0o077)
    try:
        db = MySQL(args.defaults_file, args.mysql, args.mysqldump)
        result = backup(db, args.schema, args.files_root, args.output) if args.command == "backup" else restore(db, args.archive, args.target_schema, args.files_target)
        print(json.dumps(result, sort_keys=True))
        return 0
    except (BackupError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, RuntimeError):
        # Do not expose subprocess errors, SQL/row contents, filenames from malformed bundles,
        # or credentials embedded in third-party exceptions.
        error = sys.exc_info()[1]
        print("Recovery refused: " + (str(error) if isinstance(error, BackupError) else "invalid input or I/O failure; no private diagnostics printed"), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
