#!/usr/bin/env python3
"""Fast safety/format tests. No server, credentials, or application data required."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("local_backup", Path(__file__).with_name("local_backup.py"))
backup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(backup)


class FakeDB:
    def __init__(self):
        self.created = []
        self.existing = False
        self.fail_dump = False
        self.changed = False
        self.snapshots = 0

    def query(self, sql, schema=None):
        if sql.startswith("CREATE DATABASE"):
            self.created.append(sql)
        return "1" if self.existing else "0"

    def snapshot(self, schema):
        self.snapshots += 1
        return {"tables": [{"name": "note"}], "row_counts": {"note": 2 if self.changed and self.snapshots > 1 else 1}, "foreign_keys": [], "migrations": [{"version": "12"}]}

    def check_assets(self, schema, files):
        return 1

    def dump(self, schema, destination):
        Path(destination).write_bytes(b"CREATE TABLE note (id int);\n")
        if self.fail_dump:
            raise backup.BackupError("Synthetic dump failure")

    def options(self, program):
        return []

    mysql = "fake-mysql"

    def run(self, *args, **kwargs):
        return b""


class RecoverySafetyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.files = self.root / "source"
        self.files.mkdir(mode=0o700)
        (self.files / "中文 🧪.bin").write_bytes(b"\x00\xffsynthetic\n")
        self.output = self.root / "backup.zip"
        self.target = self.root / "restored-files"
        self.db = FakeDB()

    def tearDown(self):
        self.temporary.cleanup()

    def create(self):
        return backup.backup(self.db, "synthetic_source", self.files, self.output)

    def load(self):
        return backup.restore(self.db, self.output, "rv_restore_12345678", self.target)

    def modify_archive(self, change):
        with zipfile.ZipFile(self.output) as old:
            values = {entry.filename: old.read(entry) for entry in old.infolist()}
        change(values)
        self.output.unlink()
        with zipfile.ZipFile(self.output, "w") as archive:
            for name, value in values.items():
                archive.writestr(name, value)
        self.output.chmod(0o600)

    def test_roundtrip_byte_hashes_permissions_and_no_config(self):
        self.create()
        self.assertEqual(stat.S_IMODE(self.output.stat().st_mode), 0o600)
        result = self.load()
        self.assertEqual(result["result"], "restore_verified")
        self.assertEqual(backup.file_manifest(self.files), backup.file_manifest(self.target))
        self.assertEqual(stat.S_IMODE(self.target.stat().st_mode), 0o700)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(set(archive.namelist()), {"manifest.json", "database.sql", "files/中文 🧪.bin"})
        self.assertEqual(len(self.db.created), 1)

    def test_existing_backup_not_overwritten(self):
        self.output.write_bytes(b"existing")
        with self.assertRaisesRegex(backup.BackupError, "already exists"):
            self.create()
        self.assertEqual(self.output.read_bytes(), b"existing")

    def test_existing_database_not_changed(self):
        self.create()
        self.db.existing = True
        with self.assertRaisesRegex(backup.BackupError, "database already exists"):
            self.load()
        self.assertFalse(self.db.created)
        self.assertFalse(self.target.exists())

    def test_existing_files_not_changed(self):
        self.create()
        self.target.mkdir()
        (self.target / "sentinel").write_text("keep")
        with self.assertRaisesRegex(backup.BackupError, "target already exists"):
            self.load()
        self.assertFalse(self.db.created)
        self.assertEqual((self.target / "sentinel").read_text(), "keep")

    def test_restore_rejects_non_rehearsal_schema(self):
        with self.assertRaises(backup.BackupError):
            backup.restore(self.db, self.output, "production", self.target)
        self.assertFalse(self.db.created)

    def test_failure_does_not_publish_partial_backup_or_leave_temp(self):
        self.db.fail_dump = True
        with self.assertRaises(backup.BackupError):
            self.create()
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.root.glob(".backup-*")), [])

    def test_changed_database_not_published(self):
        self.db.changed = True
        with self.assertRaisesRegex(backup.BackupError, "changed"):
            self.create()
        self.assertFalse(self.output.exists())

    def test_secret_files_fail_closed(self):
        for name in (".env", "a.key", "a.pem", "my.cnf", ".env.local", "secret.p12"):
            with self.subTest(name=name):
                path = self.files / name
                path.write_text("SYNTHETIC_SECRET_DO_NOT_PACKAGE")
                with self.assertRaisesRegex(backup.BackupError, "Secret"):
                    self.create()
                path.unlink()
        self.assertFalse(self.output.exists())

    def test_source_symlink_is_refused(self):
        (self.files / "link").symlink_to(self.root)
        with self.assertRaisesRegex(backup.BackupError, "symlink"):
            self.create()

    def test_output_inside_source_is_refused(self):
        with self.assertRaisesRegex(backup.BackupError, "outside"):
            backup.backup(self.db, "source", self.files, self.files / "backup.zip")

    def test_loose_parent_permissions_refused(self):
        self.root.chmod(0o755)
        with self.assertRaisesRegex(backup.BackupError, "700"):
            self.create()
        self.root.chmod(0o700)

    def test_traversal_unlisted_and_secret_members_refused_before_database_create(self):
        for name in ("../outside", "/tmp/outside", "files/a/../../outside", "files\\outside", "files/.env", "unlisted.txt"):
            with self.subTest(name=name):
                if self.output.exists():
                    self.output.unlink()
                self.create()
                self.modify_archive(lambda values: values.update({name: b"unexpected"}))
                with self.assertRaises(backup.BackupError):
                    self.load()
                self.assertFalse(self.db.created)
                self.assertFalse(self.target.exists())

    def test_wrong_hash_refused_before_database_create(self):
        self.create()
        self.modify_archive(lambda values: values.update({"files/中文 🧪.bin": b"\x00\xffchanged!!\n"}))
        with self.assertRaises(backup.BackupError):
            self.load()
        self.assertFalse(self.db.created)
        self.assertEqual(list(self.root.glob(".restore-*")), [])

    def test_symlink_zip_member_refused(self):
        self.create()
        with zipfile.ZipFile(self.output, "a") as archive:
            info = zipfile.ZipInfo("files/link")
            info.create_system = 3
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(info, "/etc/passwd")
        with self.assertRaisesRegex(backup.BackupError, "links"):
            self.load()
        self.assertFalse(self.db.created)

    def test_duplicate_zip_members_refused(self):
        self.create()
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            with zipfile.ZipFile(self.output, "a") as archive:
                archive.writestr("database.sql", b"duplicate")
        with self.assertRaisesRegex(backup.BackupError, "Duplicate"):
            self.load()

    def test_oversized_archive_refused(self):
        self.create()
        with patch.object(backup, "MAX_BYTES", 1):
            with self.assertRaisesRegex(backup.BackupError, "size"):
                self.load()
        self.assertFalse(self.db.created)

    def test_invalid_private_client_config_never_runs_mysql(self):
        path = self.root / "client.cnf"
        for contents in ("[client]\nhost=database.example\nuser=fixture\n", "[client]\nhost=127.0.0.1\nuser=fixture\ninit-command=DELETE FROM note\n", "!include /etc/mysql/my.cnf\n[client]\nhost=127.0.0.1\nuser=fixture\n"):
            path.write_text(contents)
            path.chmod(0o600)
            with patch.object(backup.subprocess, "run") as process:
                with self.assertRaises(backup.BackupError):
                    backup.MySQL(path)
                process.assert_not_called()

    def test_password_never_in_arguments_or_inherited_mysql_environment(self):
        path = self.root / "client.cnf"
        path.write_text("[client]\nhost=127.0.0.1\nuser=fixture\npassword=synthetic-secret\n")
        path.chmod(0o600)
        import subprocess
        results = [subprocess.CompletedProcess([], 0, b"mysql Ver 8.4.6", b""),
                   subprocess.CompletedProcess([], 0, b"mysqldump Ver 8.4.6", b""),
                   subprocess.CompletedProcess([], 0, b"8.4.6\n", b"")]
        with patch.dict(os.environ, {"MYSQL_PWD": "do-not-inherit", "MYSQL_TEST_LOGIN_FILE": "/not-used"}):
            with patch.object(backup.subprocess, "run", side_effect=results) as process:
                backup.MySQL(path)
                for call in process.call_args_list:
                    self.assertNotIn("synthetic-secret", str(call.args))
                    self.assertFalse(any(key.startswith("MYSQL") for key in call.kwargs["env"]))

    def test_assets_require_matching_size_and_sha(self):
        db = object.__new__(backup.MySQL)
        with patch.object(db, "json_rows", return_value=[{"path": "missing.bin", "size": 1, "sha256": "a" * 64}]):
            with self.assertRaisesRegex(backup.BackupError, "missing/different"):
                db.check_assets("source", {})


if __name__ == "__main__":
    os.umask(0o077)
    unittest.main()
