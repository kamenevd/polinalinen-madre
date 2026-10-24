"""Exercise deploy/rollback exclusively under a temporary fixture root."""
import importlib.util
import json
import os
import sqlite3
import subprocess
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("deployment", Path(__file__).resolve().parents[1] / "ops/deployment.py")
deployment = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(deployment)


class DeploymentSafety(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="madre-deploy-fixture-")
        self.work = Path(self.temp.name)
        self.root = self.work / "voice"
        self.old = self.root / "releases/old/backend/alice"
        self.old.mkdir(parents=True)
        (self.old / "app.py").write_text("# old source\n")
        (self.root / "current").symlink_to(self.old)
        self.legacy = self.work / "untouched-reader"
        self.legacy.mkdir()
        (self.legacy / "legacy.py").write_text("# preserved old reader\n")
        self.config = self.work / "protected"
        self.config.mkdir()
        (self.config / "secret.env").write_text("TEST_ONLY=synthetic\n")
        self.db = self.work / "voice.sqlite"
        self.conn = sqlite3.connect(self.db)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA wal_autocheckpoint=0")
        self.conn.execute("CREATE TABLE authority(id INTEGER PRIMARY KEY,revoked INTEGER)")
        self.conn.execute("PRAGMA user_version=1")
        self.conn.execute("INSERT INTO authority VALUES(1,0)")
        self.conn.commit()
        self.backup = self.work / "backup"
        deployment.backup(self.backup, self.root, self.db, [self.config, self.legacy])
        self.repo = self.work / "source"
        (self.repo / "backend/alice").mkdir(parents=True)
        (self.repo / "backend/alice/app.py").write_text("# candidate\n")
        self.git("init", "-q")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "user.name", "Fixture")
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")
        self.sha = self.git("rev-parse", "HEAD").strip()
        self.target = deployment.stage(self.repo, self.sha, self.root)

    def tearDown(self):
        self.conn.close()
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], text=True)

    def test_online_wal_backup_integrity_modes_archive_and_no_overwrite(self):
        meta = deployment.verify_backup(self.backup)
        self.assertEqual(meta["schema"], 1)
        self.assertEqual(self.backup.stat().st_mode & 0o777, 0o700)
        for path in self.backup.iterdir():
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        with sqlite3.connect(self.backup / "voice.sqlite") as backup:
            self.assertEqual(backup.execute("SELECT * FROM authority").fetchall(), [(1, 0)])
        with self.assertRaises(FileExistsError):
            deployment.backup(self.backup, self.root, self.db, [self.config])

    def test_switch_rollback_keep_latest_database_and_legacy_reader(self):
        baseline = deployment.files([self.legacy])
        deployment.switch(self.backup, self.target, is_inactive=lambda: True)
        self.assertEqual((self.root / "current").resolve(), self.target)
        self.conn.execute("UPDATE authority SET revoked=1")
        self.conn.execute("CREATE TABLE yandex_bindings(uid TEXT)")
        self.conn.execute("PRAGMA user_version=2")
        self.conn.commit()
        deployment.switch(self.backup, self.target, rollback=True, is_inactive=lambda: True)
        self.assertEqual((self.root / "current").resolve(), self.old)
        self.assertEqual(self.conn.execute("SELECT revoked FROM authority").fetchone()[0], 1)
        self.assertEqual(self.conn.execute("PRAGMA user_version").fetchone()[0], 2)
        self.assertEqual(deployment.files([self.legacy]), baseline)

    def test_schema3_rollback_keeps_latest_db_and_refuses_future_schema(self):
        deployment.switch(self.backup, self.target, is_inactive=lambda: True)
        self.conn.execute("UPDATE authority SET revoked=1")
        self.conn.execute("PRAGMA user_version=999")
        self.conn.commit()
        with self.assertRaisesRegex(RuntimeError, "unsupported rollback schema"):
            deployment.switch(self.backup, self.target, rollback=True, is_inactive=lambda: True)
        self.assertEqual((self.root / "current").resolve(), self.target)
        self.conn.execute("PRAGMA user_version=3")
        self.conn.commit()
        deployment.switch(self.backup, self.target, rollback=True, is_inactive=lambda: True)
        self.assertEqual((self.root / "current").resolve(), self.old)
        self.assertEqual(self.conn.execute("SELECT revoked FROM authority").fetchone()[0], 1)
        self.assertEqual(self.conn.execute("PRAGMA user_version").fetchone()[0], 3)

    def test_active_service_refused_without_any_lifecycle_attempt(self):
        with self.assertRaisesRegex(RuntimeError, "sanctioned inactive"):
            deployment.switch(self.backup, self.target, is_inactive=lambda: False)
        self.assertEqual((self.root / "current").resolve(), self.old)

    def test_corrupt_backup_refused(self):
        with (self.backup / "voice.sqlite").open("ab") as handle:
            handle.write(b"corruption")
        with self.assertRaisesRegex(RuntimeError, "checksum"):
            deployment.switch(self.backup, self.target, is_inactive=lambda: True)

    def test_legacy_or_config_drift_refused(self):
        (self.config / "unexpected.env").write_text("TEST_ONLY=new\n")
        with self.assertRaisesRegex(RuntimeError, "protected file drift"):
            deployment.switch(self.backup, self.target, is_inactive=lambda: True)

    def test_staged_source_drift_refused(self):
        (self.target / "app.py").write_text("# modified\n")
        with self.assertRaisesRegex(RuntimeError, "staged source drift"):
            deployment.switch(self.backup, self.target, is_inactive=lambda: True)

    def test_intervening_current_pointer_refused(self):
        (self.root / "current").unlink()
        (self.root / "current").symlink_to(self.legacy)
        with self.assertRaisesRegex(RuntimeError, "current pointer drift"):
            deployment.switch(self.backup, self.target, is_inactive=lambda: True)

    def test_dirty_source_stage_refused(self):
        (self.repo / "backend/alice/app.py").write_text("# dirty\n")
        with self.assertRaisesRegex(RuntimeError, "not clean"):
            deployment.stage(self.repo, self.sha, self.root)
