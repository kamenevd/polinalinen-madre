import json
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path

from scripts import release_cycle


class ReleaseCycleTests(unittest.TestCase):
    def releasable_state(self):
        gates = {
            name: {"status": "pass", "evidence": [f"https://ci/{name}/1"]}
            for name in release_cycle.PRE_RELEASE_GATES
        }
        gates["release"] = {"status": "pending", "evidence": []}
        return {
            "cycle": {"number": 11, "version": "5.1.0-cycle11", "stage": "releasable"},
            "gates": gates,
        }

    def test_parse_gradle_version(self):
        text = 'versionCode = 12\nversionName = "5.1.0-cycle11"\n'
        self.assertEqual((12, "5.1.0-cycle11"), release_cycle.parse_gradle_version(text))

    def test_parse_gradle_version_rejects_missing_fields(self):
        with self.assertRaisesRegex(release_cycle.ReleaseError, "versionCode/versionName"):
            release_cycle.parse_gradle_version('versionName = "x"')

    def test_prepare_version_bumps_code_and_uses_cycle_version(self):
        text = 'versionCode = 11\nversionName = "5.0.0-cycle10"\n'
        updated, code = release_cycle.prepare_gradle_version(text, "5.1.0-cycle11")
        self.assertEqual(12, code)
        self.assertIn("versionCode = 12", updated)
        self.assertIn('versionName = "5.1.0-cycle11"', updated)

    def test_version_code_must_increase_over_previous_release(self):
        release_cycle.ensure_version_code_increases(12, 11)
        with self.assertRaisesRegex(release_cycle.ReleaseError, "must be greater"):
            release_cycle.ensure_version_code_increases(11, 11)
        with self.assertRaisesRegex(release_cycle.ReleaseError, "must be greater"):
            release_cycle.ensure_version_code_increases(10, 11)
        release_cycle.ensure_version_code_increases(1, None)
        with self.assertRaisesRegex(release_cycle.ReleaseError, "previous release tag"):
            release_cycle.ensure_version_code_increases(14, None)

    def test_incomplete_gate_blocks_release(self):
        state = self.releasable_state()
        state["gates"]["runtime"]["status"] = "pending"
        with self.assertRaisesRegex(release_cycle.ReleaseError, "runtime gate"):
            release_cycle.ensure_releasable(state)

    def test_wrong_stage_blocks_release(self):
        state = self.releasable_state()
        state["cycle"]["stage"] = "validating"
        with self.assertRaisesRegex(release_cycle.ReleaseError, "releasable"):
            release_cycle.ensure_releasable(state)

    def test_artifact_names_are_canonical(self):
        self.assertEqual(
            {
                "apk": "madre-v5.1.0-cycle11.apk",
                "source": "madre-v5.1.0-cycle11-src.tar.gz",
                "manifest": "madre-v5.1.0-cycle11-manifest.json",
            },
            release_cycle.artifact_names("5.1.0-cycle11"),
        )

    def test_artifact_names_reject_path_characters(self):
        with self.assertRaises(release_cycle.ReleaseError):
            release_cycle.artifact_names("../../escape")

    def test_manifest_verification_detects_tampering(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk = root / "app.apk"
            source = root / "src.tar.gz"
            apk.write_bytes(b"apk-v1")
            source.write_bytes(b"src-v1")
            manifest = release_cycle.make_manifest("5.1.0-cycle11", 12, apk, source)
            self.assertEqual([], release_cycle.verify_manifest(manifest, root))
            apk.write_bytes(b"tampered")
            errors = release_cycle.verify_manifest(manifest, root)
            self.assertTrue(any("sha256 mismatch" in error for error in errors))

    def test_manifest_round_trip_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk = root / "app.apk"
            source = root / "src.tar.gz"
            apk.write_bytes(b"apk")
            source.write_bytes(b"src")
            manifest = release_cycle.make_manifest("5.1.0-cycle11", 12, apk, source)
            encoded = json.dumps(manifest)
            self.assertEqual(manifest, json.loads(encoded))

    def test_archive_path_guard_rejects_traversal(self):
        with self.assertRaisesRegex(release_cycle.ReleaseError, "unsafe path"):
            release_cycle.safe_archive_relative(Path("../escape"))

    def test_source_archive_is_reproducible(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            (root / "b.txt").write_text("b", encoding="utf-8")
            (root / "a.txt").write_text("a", encoding="utf-8")
            executable = root / "run.sh"
            executable.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            executable.chmod(0o755)
            subprocess.run(["git", "add", "a.txt", "b.txt", "run.sh"], cwd=root, check=True)
            first = root / "first.tar.gz"
            second = root / "second.tar.gz"
            release_cycle.create_reproducible_source_archive(root, first)
            release_cycle.create_reproducible_source_archive(root, second)
            self.assertEqual(release_cycle.sha256_file(first), release_cycle.sha256_file(second))
            with tarfile.open(first, "r:gz") as archive:
                members = archive.getmembers()
            self.assertEqual(
                ["madre/a.txt", "madre/b.txt", "madre/run.sh"],
                [item.name for item in members],
            )
            self.assertEqual([0o644, 0o644, 0o755], [item.mode for item in members])
            self.assertTrue(all(item.mtime == 0 for item in members))


if __name__ == "__main__":
    unittest.main()
