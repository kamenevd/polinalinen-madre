import json
import os
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path

from scripts import release_cycle


def gradle_text(code, name):
    return (
        "android {\n"
        "    defaultConfig {\n"
        f"        versionCode = {code}\n"
        f'        versionName = "{name}"\n'
        "    }\n"
        "}\n"
    )


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

    def init_repo(self, root):
        subprocess.run(["git", "init", "-q", "-b", "main"], cwd=root, check=True)
        subprocess.run(["git", "config", "user.email", "release@example.com"], cwd=root, check=True)
        subprocess.run(["git", "config", "user.name", "Release Test"], cwd=root, check=True)

    def tag_commit(self, root, tag, gradle, day):
        """Commit `gradle` (None deletes app/build.gradle.kts) and tag it on a distinct date."""
        path = root / "app" / "build.gradle.kts"
        path.parent.mkdir(parents=True, exist_ok=True)
        if gradle is None:
            path.unlink(missing_ok=True)
        else:
            path.write_text(gradle, encoding="utf-8")
        (root / "marker.txt").write_text(tag, encoding="utf-8")
        stamp = f"2026-01-{day:02d}T00:00:00+00:00"
        env = {**os.environ, "GIT_AUTHOR_DATE": stamp, "GIT_COMMITTER_DATE": stamp}
        subprocess.run(["git", "add", "-A"], cwd=root, check=True)
        subprocess.run(["git", "commit", "-q", "-m", tag], cwd=root, check=True, env=env)
        subprocess.run(["git", "tag", tag], cwd=root, check=True, env=env)

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

    def test_previous_release_excludes_the_tag_being_released(self):
        # Regression: a tag-triggered release read its own tag as the previous
        # release and rejected its own versionCode (v5.4.2: 20 vs previous 20).
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.init_repo(root)
            self.tag_commit(root, "v5.4.1-cycle14", gradle_text(19, "5.4.1-cycle14"), 1)
            self.tag_commit(root, "v5.4.2-cycle14", gradle_text(20, "5.4.2-cycle14"), 2)
            self.assertEqual(20, release_cycle.previous_release_version_code(root))
            self.assertEqual(
                19, release_cycle.previous_release_version_code(root, "v5.4.2-cycle14")
            )
            self.assertEqual(
                19, release_cycle.previous_release_version_code(root, "refs/tags/v5.4.2-cycle14")
            )

    def test_previous_release_compares_against_the_prior_tag(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.init_repo(root)
            self.tag_commit(root, "v5.4.1-cycle14", gradle_text(19, "5.4.1-cycle14"), 1)
            self.tag_commit(root, "v5.4.2-cycle14", gradle_text(20, "5.4.2-cycle14"), 2)
            previous = release_cycle.previous_release_version_code(root, "v5.4.2-cycle14")
            release_cycle.ensure_version_code_increases(20, previous)
            with self.assertRaisesRegex(release_cycle.ReleaseError, "must be greater"):
                release_cycle.ensure_version_code_increases(19, previous)
            with self.assertRaisesRegex(release_cycle.ReleaseError, "must be greater"):
                release_cycle.ensure_version_code_increases(18, previous)

    def test_previous_release_is_absent_when_only_the_current_tag_exists(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.init_repo(root)
            self.tag_commit(root, "v1.0.0-cycle1", gradle_text(1, "1.0.0-cycle1"), 1)
            self.assertIsNone(release_cycle.previous_release_version_code(root, "v1.0.0-cycle1"))
            # Fail-closed: no previous release still blocks anything past the first build.
            with self.assertRaisesRegex(release_cycle.ReleaseError, "previous release tag"):
                release_cycle.ensure_version_code_increases(2, None)

    def test_previous_release_is_absent_without_any_tag(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.init_repo(root)
            self.tag_commit(root, "start", gradle_text(1, "1.0.0-cycle1"), 1)
            subprocess.run(["git", "tag", "-d", "start"], cwd=root, check=True, capture_output=True)
            self.assertIsNone(release_cycle.previous_release_version_code(root, "v1.0.0-cycle1"))

    def test_previous_release_ignores_malformed_and_unrelated_tags(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.init_repo(root)
            self.tag_commit(root, "v5.4.0-cycle14", gradle_text(18, "5.4.0-cycle14"), 1)
            # Not a release tag name at all.
            self.tag_commit(root, "nightly-2026-01-02", gradle_text(99, "9.9.9"), 2)
            # Release-shaped tag whose app metadata cannot be parsed.
            self.tag_commit(root, "v9.9.8", "versionCode = 1\nversionCode = 2\n", 3)
            # Release-shaped tag with no app build file at all.
            self.tag_commit(root, "v9.9.9", None, 4)
            self.assertEqual(
                18, release_cycle.previous_release_version_code(root, "v5.4.2-cycle14")
            )

    def test_release_tag_pattern_accepts_shipped_tags_only(self):
        for tag in ("v1.0", "v1.10", "v5.3.1-cycle13", "v5.4.2-cycle14"):
            self.assertTrue(release_cycle.is_release_tag(tag), tag)
        for tag in ("", "v", "nightly", "release-2026", "v5.4.2-cycle14^{}", "5.4.2"):
            self.assertFalse(release_cycle.is_release_tag(tag), tag)

    def test_package_cli_accepts_the_tag_being_released(self):
        args = release_cycle.build_parser().parse_args(
            ["package", "--apk", "app.apk", "--current-tag", "v5.4.3-cycle14"]
        )
        self.assertEqual("v5.4.3-cycle14", args.current_tag)
        default = release_cycle.build_parser().parse_args(["package", "--apk", "app.apk"])
        self.assertIsNone(default.current_tag)

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
