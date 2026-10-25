#!/usr/bin/env python3
"""Executable stdlib fixtures; every deletion happens under TemporaryDirectory."""
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'weekly_generated_cleanup.py'
spec = importlib.util.spec_from_file_location('cleanup', SCRIPT)
assert spec is not None and spec.loader is not None
c = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = c
spec.loader.exec_module(c)


class CleanupTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='madre-cleanup-', dir=os.environ.get('TMPDIR'))
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        work = base / 'work'
        work.mkdir()
        self.l = c.Layout(work, work / 'madre-apk', base / 'caches',
                          work / 'madre-integrator', base / 'gradle.lock')
        self.l.apk.mkdir()
        self.l.cache.mkdir()
        self.repo(self.l.integrator, 'feat/razvorot-7-0-1')
        (self.l.apk / 'INDEX.txt').write_text('')
        self.now = time.time() + 30 * c.DAY
        for i in range(9):
            p = self.l.apk / f'6.0.{i}.apk'
            p.write_bytes(f'apk {i}'.encode())
            os.utime(p, (1000 + i, 1000 + i))

    def cmd(self, repo, *args):
        return c.git(repo, *args)

    def repo(self, path, branch='old/integrated'):
        path.mkdir(parents=True, exist_ok=True)
        self.cmd(path, 'init', '-q', '-b', branch)
        self.cmd(path, 'config', 'user.email', 'fixture@example.invalid')
        self.cmd(path, 'config', 'user.name', 'Fixture')
        (path / 'app/src/main').mkdir(parents=True)
        (path / 'app/src/main/AndroidManifest.xml').write_text('<manifest/>')
        (path / '.gitignore').write_text('build/\n')
        self.cmd(path, 'add', '.')
        self.cmd(path, 'commit', '-qm', 'initial')
        (path / '.git/madre-ci').mkdir()
        (path / '.git/madre-ci/gradlew.original').write_text('fixture registration')

    def clone(self, branch='old/integrated'):
        path = self.l.work / ('madre-' + branch.replace('/', '-'))
        subprocess.run(['git', 'clone', '-q', str(self.l.integrator), str(path)], check=True)
        self.cmd(path, 'checkout', '-qb', branch)
        self.cmd(path, 'config', 'user.email', 'fixture@example.invalid')
        self.cmd(path, 'config', 'user.name', 'Fixture')
        (path / '.git/madre-ci').mkdir()
        (path / '.git/madre-ci/gradlew.original').write_text('fixture registration')
        (path / 'app/build').mkdir()
        (path / 'app/build/generated.bin').write_text('generated')
        return path

    def plan(self, **kw):
        return c.plan(self.l, active=set(), daemon=False, now=self.now, **kw)

    def targets(self, result=None):
        return {Path(x['path']).name for x in (result or self.plan())['candidates']}

    def test_top_five(self):
        self.assertEqual(self.targets(), {f'6.0.{i}.apk' for i in range(4)})

    def test_index_absolute_relative_and_accepted_or_ambiguous_status(self):
        for ref in (str(self.l.apk / '6.0.0.apk'), './6.0.0.apk', '6.0.0.apk accepted', '6.0.0.apk unclear'):
            with self.subTest(ref=ref):
                (self.l.apk / 'INDEX.txt').write_text(ref)
                self.assertNotIn('6.0.0.apk', self.targets())

    def test_index_sha_protects_renamed_copy(self):
        sha = c.digest(self.l.apk / '6.0.0.apk')
        (self.l.apk / 'INDEX.txt').write_text(sha + ' absent.apk')
        self.assertNotIn('6.0.0.apk', self.targets())

    def test_ambiguous_or_missing_index_keeps_all(self):
        (self.l.apk / 'INDEX.txt').write_text('accepted but path is missing')
        self.assertFalse(self.targets())
        (self.l.apk / 'INDEX.txt').unlink()
        self.assertFalse(self.targets())

    def test_latest_and_current_candidate(self):
        (self.l.apk / 'latest.apk').symlink_to('6.0.0.apk')
        for name in ('7.1.0.apk', '7.1.1-7af-candidate.apk', 'mystery.apk'):
            p = self.l.apk / name
            p.write_text('protected')
            os.utime(p, (0, 0))
        self.assertNotIn('6.0.0.apk', self.targets())
        self.assertNotIn('7.1.0.apk', self.targets())
        self.assertNotIn('7.1.1-7af-candidate.apk', self.targets())
        self.assertNotIn('mystery.apk', self.targets())

    def test_receipts_and_non_apk_survive_apply(self):
        p = self.l.apk / 'protected-receipt.json'
        p.write_text(json.dumps({'artifact': '6.0.0.apk'}))
        result = c.run(self.l, apply=True, active=set(), daemon=False, now=self.now)
        self.assertEqual(result['errors'], [])
        self.assertTrue(result['preservation_verified'])
        self.assertTrue(p.exists())
        self.assertTrue((self.l.apk / '6.0.0.apk').exists())
        self.assertFalse((self.l.apk / '6.0.1.apk').exists())
        self.assertTrue((self.l.apk / '6.0.8.apk').exists())
        self.assertIsInstance(result['actual_freed_bytes_statvfs'], int)

    def test_default_dry_run_never_mutates(self):
        before = {p.name: p.read_bytes() for p in self.l.apk.iterdir()}
        result = c.run(self.l, active=set(), daemon=False, now=self.now)
        self.assertEqual(result['deleted'], [])
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.l.apk.iterdir()})

    def test_symlink_escape_never_followed(self):
        outside = Path(self.tmp.name) / 'outside.apk'
        outside.write_text('source')
        (self.l.apk / '6.0.0.apk').unlink()
        (self.l.apk / '6.0.0.apk').symlink_to(outside)
        c.run(self.l, apply=True, active=set(), daemon=False, now=self.now)
        self.assertEqual(outside.read_text(), 'source')
        self.assertTrue((self.l.apk / '6.0.0.apk').is_symlink())

    def test_cache_age_boundary_access_lock_daemon(self):
        p = self.l.cache / 'modules-2/files-2.1/group/name/1/hash/file.jar'
        p.parent.mkdir(parents=True)
        p.write_text('dependency')
        boundary = self.now - 14 * c.DAY
        os.utime(p, (boundary, boundary))
        self.assertNotIn('file.jar', self.targets())
        os.utime(p, (boundary - 1, boundary - 1))
        self.assertIn('file.jar', self.targets())
        os.utime(p, (self.now, boundary - 1))
        self.assertNotIn('file.jar', self.targets())
        os.utime(p, (boundary - 1, boundary - 1))
        lock = self.l.cache / 'modules-2/modules-2.lock'
        lock.write_text('lock')
        self.assertNotIn('file.jar', self.targets())
        lock.unlink()
        self.assertNotIn('file.jar', self.targets(c.plan(self.l, active=set(), daemon=True, now=self.now)))

    def test_cache_non_payload_paths_never_traversed(self):
        p = self.l.cache / 'daemon/state.bin'
        p.parent.mkdir()
        p.write_text('current')
        self.assertNotIn('state.bin', self.targets())

    def test_clean_integrated_build_only_apply_sources_stay(self):
        repo = self.clone()
        before = c.source_manifest(repo)
        result = c.run(self.l, apply=True, active=set(), daemon=False, now=self.now)
        self.assertEqual(result['errors'], [])
        self.assertFalse((repo / 'app/build').exists())
        self.assertEqual(c.source_manifest(repo), before)
        self.assertTrue(repo.exists())  # standalone clone is NEVER a removable worktree
        self.assertEqual(result['worktree_removals'], 0)

    def test_active_open_file_and_cwd_protect_workspace(self):
        repo = self.clone()
        for active in ({repo}, {repo / 'app/build/generated.bin'}):
            result = c.plan(self.l, active=active, daemon=False, now=self.now)
            self.assertEqual(result['counts']['build'], 0)

    def test_dirty_workspace(self):
        repo = self.clone()
        (repo / 'untracked.txt').write_text('work')
        self.assertEqual(self.plan()['counts']['build'], 0)

    def test_unmerged_workspace(self):
        repo = self.clone()
        (repo / 'untracked.txt').write_text('work')
        self.cmd(repo, 'add', '.')
        self.cmd(repo, 'commit', '-qm', 'not integrated')
        self.assertEqual(self.plan()['counts']['build'], 0)

    def test_ci_wip_integrator_protection(self):
        self.clone('ci/test')
        self.clone('wip/test')
        (self.l.integrator / 'app/build').mkdir()
        self.assertEqual(self.plan()['counts']['build'], 0)

    def test_locked_workspace(self):
        repo = self.clone()
        (repo / '.git/index.lock').write_text('locked')
        self.assertEqual(self.plan()['counts']['build'], 0)

    def test_generated_symlink_and_mount_refused(self):
        repo = self.clone()
        outside = Path(self.tmp.name) / 'outside'
        outside.mkdir()
        (repo / 'app/build/escape').symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError, 'unsafe generated'):
            self.plan()
        (repo / 'app/build/escape').unlink()
        original = os.path.ismount
        with patch.object(c.os.path, 'ismount', side_effect=lambda p: p == repo / 'app/build' or original(p)):
            with self.assertRaisesRegex(RuntimeError, 'unsafe generated'):
                self.plan()

    def test_no_104_or_evidence_traversal(self):
        forbidden = self.l.work / 'lxc104-materials-import'
        forbidden.mkdir()
        (forbidden / 'sentinel').write_text('untouched')
        evidence = self.l.work / 'madre-evidence'
        evidence.mkdir()
        original = Path.iterdir
        def guarded(p):
            if p == forbidden or p == evidence:
                raise AssertionError('forbidden traversal')
            return original(p)
        with patch.object(Path, 'iterdir', guarded):
            self.plan()
        self.assertEqual((forbidden / 'sentinel').read_text(), 'untouched')

    def test_common_lock_conflict(self):
        with self.l.lock.open('a') as handle:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaisesRegex(RuntimeError, 'lock busy'):
                c.run(self.l, active=set(), daemon=False, now=self.now)

    def test_changed_candidate_fails_closed(self):
        result = self.plan()
        candidate = result['candidates'][0]
        p = Path(candidate['path'])
        p.write_text('changed')
        with self.assertRaisesRegex(RuntimeError, 'changed'):
            c.remove(candidate)
        self.assertTrue(p.exists())

    def test_process_probe_real(self):
        active, daemon = c.process_state()
        self.assertIn(Path.cwd(), active)
        self.assertIsInstance(daemon, bool)


if __name__ == '__main__':
    unittest.main(verbosity=2)
