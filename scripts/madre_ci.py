#!/usr/bin/env python3
"""Local-only Madre build admission. Installation is explicit, never automatic."""
import argparse
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

FEATURE = 'refs/heads/feat/razvorot-7-0-1'
INTEGRATOR = Path('/root/.hermes/work/madre-71-integrator-20261025')
LOCK = Path('/root/.cache/madre/gradle.lock')
TASKS = [':app:compileDebugKotlin', ':app:compileReleaseKotlin', ':app:testDebugUnitTest', '-Pmadre.shortApkGate=true']
ROOT = Path(__file__).resolve().parents[1]


def git(*args, cwd=None):
    return subprocess.check_output(['git', *args], cwd=cwd, text=True).strip()


def root():
    return Path(git('rev-parse', '--show-toplevel')).resolve()


def clean_head(repo, expected):
    if not re.fullmatch('[0-9a-f]{40}', expected):
        raise RuntimeError('full SHA required')
    if git('rev-parse', 'HEAD', cwd=repo) != expected or git('status', '--porcelain', cwd=repo):
        raise RuntimeError('dirty or stale HEAD')
    gd = Path(git('rev-parse', '--absolute-git-dir', cwd=repo))
    if any((gd / n).exists() for n in ['MERGE_HEAD', 'CHERRY_PICK_HEAD', 'REVERT_HEAD', 'rebase-merge', 'rebase-apply']):
        raise RuntimeError('integration already in flight')


@contextlib.contextmanager
def locked(path=LOCK, nonblock=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a') as fd:
        fcntl.flock(fd, fcntl.LOCK_EX | (fcntl.LOCK_NB if nonblock else 0))
        yield


def gradle(repo, args, *, raw=None):
    with locked():
        return _run_gradle(repo, args, raw=raw)


def _run_gradle(repo, args, *, raw=None):
    if any(a.startswith(('-Dorg.gradle.jvmargs', '--max-workers', '-Dorg.gradle.workers.max')) or a in ['--no-daemon', '--no-build-cache'] for a in args):
        raise RuntimeError('resource/daemon/cache policy cannot be overridden')
    if raw is None:
        configured = subprocess.run(['git', 'config', '--get', 'madre.rawGradlew'], cwd=repo, text=True, capture_output=True)
        raw = Path(configured.stdout.strip()) if configured.returncode == 0 else repo / 'gradlew'
    command = ['bash', str(raw), *args, '--daemon', '--build-cache', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8']
    # Inherit client/signing metadata unchanged. Never print command or environment.
    env = os.environ.copy()
    env.setdefault('ANDROID_HOME', '/opt/android-sdk')
    env.setdefault('ANDROID_SDK_ROOT', '/opt/android-sdk')
    return subprocess.run(command, cwd=repo, env=env, timeout=1800).returncode


def refs_allowed(repo, text, head):
    lines = text.splitlines()
    if not lines:
        raise RuntimeError('empty push admission input')
    for line in lines:
        fields = line.split()
        if len(fields) != 4:
            raise RuntimeError('malformed pre-push ref')
        localref, sha, remote, old = fields
        if sha != head or not re.fullmatch('[0-9a-f]{40}', old):
            raise RuntimeError('push SHA must equal tested clean HEAD; deletion forbidden')
        if remote == FEATURE:
            if repo.resolve() != INTEGRATOR.resolve() or localref != FEATURE:
                raise RuntimeError('feature push only from pinned integrator realpath')
        elif not (remote.startswith('refs/heads/wip/') or remote.startswith('refs/heads/ci/')):
            raise RuntimeError('workers may push only wip/* or ci/*')


def gate(repo, refs):
    # Check routing before queueing for the expensive global build admission.
    refs_allowed(repo, refs, git('rev-parse', 'HEAD', cwd=repo))
    with locked():
        return _gate(repo, refs)


def _gate(repo, refs):
    head = git('rev-parse', 'HEAD', cwd=repo)
    refs_allowed(repo, refs, head)  # Deny unauthorized target BEFORE build.
    clean_head(repo, head)
    gd = Path(git('rev-parse', '--absolute-git-dir', cwd=repo))
    evidence = gd / 'madre-ci' / ('gate-' + str(time.time_ns()))
    evidence.mkdir(parents=True)
    sources = {p: hashlib.sha256((repo / p).read_bytes()).hexdigest() for p in git('ls-files', cwd=repo).splitlines() if (repo / p).is_file()}
    (evidence / 'sources.json').write_text(json.dumps({'head': head, 'files': sources}, indent=2))
    results = repo / 'app/build/test-results/testDebugUnitTest'
    if results.exists():
        shutil.rmtree(results)
    # Disable both up-to-date and FROM-CACHE for the executed short unit gate only.
    init = evidence / 'execute-units.gradle'
    init.write_text("allprojects { tasks.withType(Test).configureEach { if (name == 'testDebugUnitTest') { outputs.upToDateWhen { false }; outputs.cacheIf { false } } } }\n")
    with (evidence / 'gradle.log').open('w') as log:
        with contextlib.redirect_stdout(log):
            # Child stdout must be redirected at the descriptor boundary.
            saved = os.dup(1); savederr = os.dup(2)
            try:
                os.dup2(log.fileno(), 1); os.dup2(log.fileno(), 2)
                rc = _run_gradle(repo, TASKS + ['--init-script', str(init)])
            finally:
                os.dup2(saved, 1); os.dup2(savederr, 2); os.close(saved); os.close(savederr)
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    if results.exists():
        shutil.copytree(results, evidence / 'junit')
        for p in results.glob('TEST-*.xml'):
            suite = ET.parse(p).getroot()
            for k in totals:
                totals[k] += int(suite.get(k, 0))
    clean_head(repo, head)
    after = {p: hashlib.sha256((repo / p).read_bytes()).hexdigest() for p in sources}
    passed = rc == 0 and totals['tests'] >= 1250 and not any(totals[k] for k in ['failures', 'errors', 'skipped']) and after == sources
    receipt = {'head': head, 'passed': passed, 'gradle_exit': rc, 'junit': totals, 'evidence': str(evidence)}
    (evidence / 'receipt.json').write_text(json.dumps(receipt, indent=2))
    print(json.dumps(receipt))
    if not passed:
        raise RuntimeError('mandatory compile/release/executed-unit gate failed; see durable log')
    return receipt


def integrate(repo, expected, mode, commits):
    if repo.resolve() != INTEGRATOR.resolve():
        raise RuntimeError('not the pinned integrator')
    with locked(Path('/root/.cache/madre/integrator.lock'), nonblock=True):
        clean_head(repo, expected)
        if git('symbolic-ref', 'HEAD', cwd=repo) != FEATURE:
            raise RuntimeError('integrator must own feature branch')
        subprocess.run(['git', 'fetch', 'origin', FEATURE], cwd=repo, check=True)
        if git('rev-parse', 'FETCH_HEAD', cwd=repo) != expected:
            raise RuntimeError('remote feature moved; explicit new admission required')
        for commit in commits:
            if not re.fullmatch('[0-9a-f]{40}', commit):
                raise RuntimeError('only full integration SHAs accepted')
            git('cat-file', '-e', commit + '^{commit}', cwd=repo)
        if mode == 'ff':
            if len(commits) != 1:
                raise RuntimeError('ff takes exactly one commit')
            subprocess.run(['git', 'merge', '--ff-only', commits[0]], cwd=repo, check=True)
        else:
            subprocess.run(['git', 'cherry-pick', *commits], cwd=repo, check=True)
        print('Integrated locally. Push separately through mandatory pre-push gate.')


def install(repo, expected, inventory):
    clean_head(repo, expected)
    data = json.loads(Path(inventory).read_text())
    targets = [Path(row['path']).resolve() for row in data] + [INTEGRATOR.resolve()]
    if len(set(targets)) != len(targets):
        raise RuntimeError('duplicate installation target')
    # Fail closed on ANY dirty/in-flight workspace; never silently skip it.
    for p in targets:
        if p.parent != Path('/root/.hermes/work') or not p.name.startswith('madre-') or not (p / 'app').is_dir():
            raise RuntimeError('out-of-scope installation target')
        clean_head(p, git('rev-parse', 'HEAD', cwd=p))
        if subprocess.run(['git', 'config', '--get', 'madre.rawGradlew'], cwd=p, capture_output=True).returncode == 0:
            raise RuntimeError('already installed; explicit rollback/review required')
    # Refuse active legacy Gradle too: do not inspect/print process arguments.
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit(): continue
        try:
            if (proc / 'comm').read_text().strip() in ['java', 'gradle']:
                raise RuntimeError('Java/Gradle process active; installation requires quiescence')
        except (FileNotFoundError, PermissionError):
            continue
    # A running wrapper owns this same lock; refuse rather than interrupt it.
    with locked(nonblock=True):
        dest = Path('/root/.local/share/madre-ci-tools') / expected
        if dest.exists():
            raise RuntimeError('immutable installation already exists')
        dest.mkdir(parents=True)
        for name in ['madre_ci.py', 'pre-push', 'madre-gradle.sh']:
            shutil.copy2(repo / 'scripts' / name, dest / name)
        for p in targets:
            gd = Path(git('rev-parse', '--absolute-git-dir', cwd=p)) / 'madre-ci'
            gd.mkdir(exist_ok=True)
            # Private backup may contain credential config: never print it.
            config = Path(git('rev-parse', '--git-path', 'config', cwd=p))
            if not config.is_absolute(): config = p / config
            shutil.copy2(config, gd / 'config.before'); (gd / 'config.before').chmod(0o600)
            shutil.copy2(p / 'gradlew', gd / 'gradlew.original')
            # Keep tracked sources clean. All admitted Gradle calls use this local
            # wrapper (including manual assembleDebug), never edit old gradlew bytes.
            launcher = gd / 'madre-gradle'
            launcher.write_text('#!/bin/sh\nexec python3 ' + str(dest / 'madre_ci.py') + ' gradle "$@"\n')
            launcher.chmod(0o755)
            subprocess.run(['git', 'config', 'madre.rawGradlew', str(gd / 'gradlew.original')], cwd=p, check=True)
            subprocess.run(['git', 'config', 'core.hooksPath', str(dest)], cwd=p, check=True)
        print('Installed reviewed local hooks/wrappers; backups in each .git/madre-ci. No branch pushed.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='cmd', required=True)
    sub.add_parser('pre-push')
    sub.add_parser('gradle').add_argument('args', nargs=argparse.REMAINDER)
    p = sub.add_parser('integrate'); p.add_argument('expected'); p.add_argument('mode', choices=['ff', 'cherry-pick']); p.add_argument('commits', nargs='+')
    p = sub.add_parser('install'); p.add_argument('reviewed_sha'); p.add_argument('inventory')
    a = parser.parse_args(); repo = root()
    if a.cmd == 'gradle': return gradle(repo, a.args)
    if a.cmd == 'pre-push': gate(repo, sys.stdin.read())
    if a.cmd == 'integrate': integrate(repo, a.expected, a.mode, a.commits)
    if a.cmd == 'install': install(repo, a.reviewed_sha, a.inventory)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (RuntimeError, OSError, subprocess.SubprocessError, ValueError) as exc:
        print('BLOCKED: ' + str(exc), file=sys.stderr)
        sys.exit(1)
