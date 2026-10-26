#!/usr/bin/env python3
"""Fail-closed, local generated-file retention. CLI defaults to read-only dry-run."""
import argparse
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
from dataclasses import dataclass

DAY = 86400
WORK = Path('/root/.hermes/work')
LOCK = Path('/root/.cache/madre/gradle.lock')


@dataclass(frozen=True)
class Layout:
    work: Path = WORK
    apk: Path = WORK / 'madre-apk'
    cache: Path = Path('/root/.gradle/caches')
    integrator: Path = WORK / 'madre-71-integrator-20261025'
    lock: Path = LOCK


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args],
                                   stderr=subprocess.DEVNULL, timeout=30).decode().strip()


def safe(path, root):
    """No symlink components, mount points or device transitions, including root."""
    if not path.is_relative_to(root):
        return False
    if any(p.is_symlink() for p in (root, *root.parents)) or os.path.ismount(root):
        return False
    p = root
    device = root.stat().st_dev
    for part in path.relative_to(root).parts:
        p = p / part
        if p.is_symlink() or os.path.ismount(p) or p.lstat().st_dev != device:
            return False
    return True


def tree(path, root):
    """Manifest ONLY a pre-approved generated subtree; never follow links."""
    if not safe(path, root):
        raise RuntimeError('unsafe generated path: ' + str(path))
    items = [path]
    if path.is_dir():
        for base, dirs, files in os.walk(path, followlinks=False):
            for name in dirs + files:
                p = Path(base) / name
                if not safe(p, root) or not (p.is_dir() or p.is_file()):
                    raise RuntimeError('unsafe generated entry: ' + str(p))
                items.append(p)
    return {str(p): [p.lstat().st_dev, p.lstat().st_ino, p.lstat().st_size,
                     p.lstat().st_mtime_ns, p.lstat().st_atime_ns,
                     'dir' if p.is_dir() else digest(p)] for p in sorted(items)}


def daemon_log_busy(path):
    """Only an explicit last IDLE transition proves a daemon idle; unknown is busy."""
    state = None
    try:
        with path.open() as stream:
            for line in stream:
                if 'Marking the daemon as busy' in line:
                    state = True
                elif 'Marking the daemon as idle' in line:
                    state = False
    except (OSError, UnicodeError):
        return True
    return state is not False


def cache_lock_reason(layout):
    """Read actual kernel owners (POSIX FileLock, OFD and flock), never alter locks."""
    locks = list(layout.cache.glob('*.lock')) + list((layout.cache / 'modules-2').glob('*.lock'))
    identities = set()
    for p in locks:
        if not safe(p, layout.cache) or not p.is_file():
            return 'unsafe/unknown cache lock'
        s = p.stat()
        identities.add((os.major(s.st_dev), os.minor(s.st_dev), s.st_ino))
    try:
        for line in Path('/proc/locks').read_text().splitlines():
            parts = line.split()
            # Blocked waiters have an extra -> token; owners have the same dev:inode field.
            token = next((x for x in parts if re.fullmatch(r'[0-9a-fA-F]+:[0-9a-fA-F]+:\d+', x)), None)
            if token is None:
                return 'unknown kernel lock format'
            major, minor, inode = token.split(':')
            if (int(major, 16), int(minor, 16), int(inode)) in identities:
                return 'kernel-owned cache lock'
    except OSError:
        return 'cannot prove cache locks unowned'
    return None


def process_state():
    """All visible cwd/fd/maps targets; unreadable live processes abort admission."""
    paths, daemon = set(), False
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit():
            continue
        try:
            cmd = (proc / 'cmdline').read_bytes()
            if b'GradleWorkerMain' in cmd:
                daemon = True
            if b'GradleDaemon' in cmd:
                logs = list((Path.home() / '.gradle/daemon').glob(f'*/daemon-{proc.name}.out.log'))
                daemon |= len(logs) != 1 or daemon_log_busy(logs[0])
            for p in [proc / 'cwd', *(proc / 'fd').iterdir()]:
                try:
                    target = os.readlink(p).removesuffix(' (deleted)')
                    if target.startswith('/'):
                        paths.add(Path(target))
                except FileNotFoundError:
                    pass
            for line in (proc / 'maps').read_text().splitlines():
                columns = line.split(None, 5)
                if len(columns) == 6 and columns[5].startswith('/'):
                    paths.add(Path(columns[5].removesuffix(' (deleted)')))
        except (FileNotFoundError, ProcessLookupError):
            continue
        except PermissionError as exc:
            raise RuntimeError('cannot prove process inactivity') from exc
    return paths, daemon


class ActivePaths(set):
    """Index identical ancestry predicates once, not once per generated file."""
    def __init__(self, paths):
        super().__init__(paths)
        self.occupied = {parent for p in self for parent in (p, *p.parents)}


def busy(path, active):
    if isinstance(active, ActivePaths):
        return path in active.occupied
    return any(p == path or p.is_relative_to(path) for p in active)


def old(path, now):
    s = path.stat()
    return max(s.st_atime, s.st_mtime, s.st_ctime) < now - 14 * DAY


def workspace_reason(repo, layout, canonical, active):
    if repo in (layout.integrator, layout.work / 'madre-711-alice-ui-20261025',
                Path(__file__).resolve().parents[1]):
        return 'integrator/tool runtime'
    if not safe(repo, layout.work) or not (repo / 'app/src/main/AndroidManifest.xml').is_file():
        return 'not a known Android workspace'
    # Explicit registration is the known-workspace inventory; never inspect arbitrary clones.
    gd = Path(git(repo, 'rev-parse', '--absolute-git-dir'))
    if not (gd / 'madre-ci/gradlew.original').is_file():
        return 'unregistered workspace'
    branch = git(repo, 'symbolic-ref', '--quiet', '--short', 'HEAD')
    if branch.startswith(('ci/', 'wip/')):
        return 'active writer branch'
    if busy(repo, active):
        return 'open file/cwd/mapping'
    if git(repo, 'status', '--porcelain', '--untracked-files=all'):
        return 'dirty'
    if any((gd / name).exists() for name in ('index.lock', 'MERGE_HEAD', 'CHERRY_PICK_HEAD',
                                            'REVERT_HEAD', 'sequencer', 'BISECT_LOG',
                                            'rebase-merge', 'rebase-apply', 'locked')):
        return 'locked/integration in flight'
    head = git(repo, 'rev-parse', 'HEAD')
    if head == canonical:
        return 'current canonical HEAD'
    # Missing canonical object and unmerged commits both fail closed.
    git(layout.integrator, 'merge-base', '--is-ancestor', head, canonical)
    return None


def source_manifest(repo):
    files = git(repo, 'ls-files', '-z').split('\0')
    return {'head': git(repo, 'rev-parse', 'HEAD'),
            'status': git(repo, 'status', '--porcelain', '--untracked-files=all'),
            'files': {name: digest(repo / name) for name in files
                      if name and (repo / name).is_file() and not (repo / name).is_symlink()}}


def plan(layout=Layout(), *, active=None, daemon=None, now=None):
    now = time.time() if now is None else now
    if active is None:
        active, daemon = process_state()
    active = ActivePaths(active)
    canonical = git(layout.integrator, 'rev-parse', 'HEAD')
    result = {'schema': 1, 'mode': 'dry-run', 'created': now, 'candidates': [],
              'kept': [], 'sources': {}, 'preserved': {}, 'worktree_removals': 0,
              'worktree_policy': 'retain all source trees; no worktree removal enabled', 'errors': []}

    def keep(path, reason):
        result['kept'].append({'path': str(path), 'reason': reason})

    def add(path, root, kind):
        manifest = tree(path, root)
        if any(busy(Path(p), active) for p in manifest):
            keep(path, 'open file/cwd/mapping')
            return
        result['candidates'].append({'path': str(path), 'root': str(root), 'kind': kind,
                                     'reason': {'apk': 'unreferenced recognized artifact outside top5/latest/current',
                                                'cache': 'immutable payload unused and unchanged >14 days; no owner/open use',
                                                'build': 'registered clean inactive predecessor; canonical ancestry proven'}[kind],
                                     'manifest': manifest,
                                     'bytes': sum(v[2] for v in manifest.values() if v[5] != 'dir')})

    if layout.apk.exists():
        if not safe(layout.apk, layout.work):
            raise RuntimeError('unsafe APK root')
        entries = list(layout.apk.iterdir())  # flat only; evidence directories are never traversed
        apks = [p for p in entries if p.suffix == '.apk' and not p.is_symlink() and p.is_file()]
        metadata, unclear = '', False
        index = layout.apk / 'INDEX.txt'
        if not index.is_file() or index.is_symlink():
            unclear = True
        for p in entries:
            if p.is_symlink() or p.is_dir():
                if p.is_dir() and not p.is_symlink():
                    unclear = True  # opaque receipt/evidence directory: retain all APKs
                continue
            if p.suffix != '.apk':
                # Known preview payloads and historical latest links are not retention metadata.
                if (p.suffix.lower() == '.png' or
                        (p.suffix.lower() == '.zip' and re.fullmatch(r'\d+\.\d+\.\d+.*-png(?:-ci-red)?\.zip', p.name))):
                    keep(p, 'preview payload (not retention metadata)')
                    result['preserved'][str(p)] = digest(p)
                    continue
                # Unknown/binary metadata => protect every artifact, not guess.
                is_index = p.name.startswith(('INDEX.txt', 'INDEX-before-'))
                if (not is_index and p.suffix not in ('.txt', '.json', '.sha256')) or p.stat().st_size > 2 * 1024 * 1024:
                    unclear = True
                    continue
                try:
                    text = p.read_text()
                    if p.suffix == '.json':
                        json.loads(text)
                except (UnicodeError, OSError):
                    unclear = True
                    continue
                except ValueError:
                    unclear = True
                    continue
                metadata += '\n' + text
                result['preserved'][str(p)] = digest(p)
                if not is_index and text.strip() and not re.search(r'[^\s|]+\.apk|(?i:\b[0-9a-f]{64}\b)', text):
                    unclear = True
                if p == index and any(line.strip() and not re.search(r'[^\s|]+\.apk', line)
                                      for line in text.splitlines()):
                    unclear = True
        referenced_hashes = set(re.findall(r'(?i)\b[0-9a-f]{64}\b', metadata.lower()))
        top = set(sorted(apks, key=lambda p: (-p.stat().st_mtime_ns, p.name))[:5])
        latest = layout.apk / 'latest.apk'
        latest_target = latest.resolve() if latest.is_symlink() else latest
        for p in apks:
            sha = digest(p)
            reason = None
            if unclear:
                reason = 'ambiguous/missing metadata'
            elif p in top:
                reason = 'newest five'
            elif p.resolve() == latest_target:
                reason = 'latest target'
            elif p.name in metadata or str(p) in metadata or sha in referenced_hashes:
                reason = 'INDEX/receipt path, basename or SHA (any status)'
            elif re.fullmatch(r'\d+\.\d+\.\d+-' + canonical[:7] + r'(?:-candidate)?\.apk', p.name):
                reason = 'exact current canonical candidate'
            elif not re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9a-f]{7,40}(?:-candidate)?)?\.apk', p.name):
                reason = 'unrecognized artifact name'
            elif busy(p, active):
                reason = 'open artifact'
            if reason:
                keep(p, reason)
                result['preserved'][str(p)] = sha
            else:
                add(p, layout.apk, 'apk')
        for p in entries:
            if p.is_symlink():
                keep(p, 'symlink retained (never followed for deletion)')
                result['preserved'][str(p)] = 'symlink:' + os.readlink(p)

    if layout.cache.exists():
        if not safe(layout.cache, layout.cache):
            raise RuntimeError('unsafe cache root')
        # Only immutable dependency payloads, not daemon/transforms/journal/current caches.
        root = layout.cache / 'modules-2/files-2.1'
        lock_reason = cache_lock_reason(layout)
        if daemon or lock_reason:
            keep(layout.cache, lock_reason or 'busy/unknown Gradle daemon or worker')
        elif root.is_dir() and safe(root, layout.cache):
            # Exactly group/module/version/hash/file; no general cache recursion.
            level = [root]
            for _ in range(4):
                level = [p for parent in level for p in parent.iterdir()
                         if p.is_dir() and safe(p, layout.cache)]
            for parent in level:
                for p in parent.iterdir():
                    if p.is_file() and safe(p, layout.cache) and p.suffix in ('.jar', '.pom', '.module', '.aar'):
                        if old(p, now) and not busy(p, active):
                            add(p, layout.cache, 'cache')
                        else:
                            keep(p, 'recent access/change or open')

    result['canonical_integrator'] = canonical
    result['linked_worktree_inventory'] = git(layout.integrator, 'worktree', 'list', '--porcelain')
    # Listing immediate names is not traversal of any unapproved workspace.
    for repo in sorted(layout.work.iterdir()):
        if not repo.name.startswith('madre-') or repo == layout.apk or not repo.is_dir() or repo.is_symlink():
            continue
        if repo.name.startswith(('madre-pb-', 'madre-alice-', 'madre-yandex-A-server-')):
            continue  # explicitly excluded backend workspaces; do not inspect
        if not (repo / '.git').exists():
            continue
        try:
            reason = workspace_reason(repo, layout, canonical, active)
            if reason:
                keep(repo, reason)
                continue
            result['sources'][str(repo)] = source_manifest(repo)
            # Only these Android-generated roots; no backend, data, config or evidence scans.
            for target in (repo / 'build', repo / 'app/build', repo / 'benchmark/build'):
                if not target.exists():
                    continue
                rel = str(target.relative_to(repo))
                if git(repo, 'ls-files', '--', rel):
                    keep(target, 'tracked content')
                else:
                    add(target, repo, 'build')
        except subprocess.CalledProcessError:
            keep(repo, 'unknown branch/ancestry; not proven integrated')
    result['counts'] = {kind: sum(c['kind'] == kind for c in result['candidates'])
                        for kind in ('apk', 'cache', 'build')}
    result['candidate_bytes'] = sum(c['bytes'] for c in result['candidates'])
    return result


@contextlib.contextmanager
def locked(path, wait=0):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a') as handle:
        deadline = time.monotonic() + wait
        while True:
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    raise RuntimeError('common Gradle lock busy; no cleanup')
                time.sleep(0.1)
        yield


def preserved(result):
    for name, expected in result['preserved'].items():
        p = Path(name)
        actual = 'symlink:' + os.readlink(p) if p.is_symlink() else digest(p)
        if actual != expected:
            raise RuntimeError('protected artifact/metadata changed: ' + name)
    for name, expected in result['sources'].items():
        if source_manifest(Path(name)) != expected:
            raise RuntimeError('source/status changed: ' + name)


def remove(candidate):
    """Unlink only exact validated entries, deepest first, no shutil recursion."""
    p, root = Path(candidate['path']), Path(candidate['root'])
    # Reads change atime; compare identities/content, not atime, on revalidation.
    current = tree(p, root)
    def stable(manifest):
        return {k: v[:4] + v[5:] for k, v in manifest.items()}
    if stable(current) != stable(candidate['manifest']):
        raise RuntimeError('generated files changed before apply')
    for name, info in sorted(current.items(), key=lambda item: len(Path(item[0]).parts), reverse=True):
        target = Path(name)
        if not safe(target, root):
            raise RuntimeError('path changed during apply')
        # Anchor each component with O_NOFOLLOW; an ancestor swap cannot redirect unlink.
        fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY)
        try:
            for part in target.parent.parts[1:]:
                nxt = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                os.close(fd)
                fd = nxt
            observed = os.stat(target.name, dir_fd=fd, follow_symlinks=False)
            if [observed.st_dev, observed.st_ino] != info[:2]:
                raise RuntimeError('entry replaced during apply')
            if info[5] == 'dir':
                os.rmdir(target.name, dir_fd=fd)
            else:
                os.unlink(target.name, dir_fd=fd)
        finally:
            os.close(fd)


def run(layout=Layout(), *, apply=False, wait=0, active=None, daemon=None, now=None):
    with locked(layout.lock, wait):
        before = os.statvfs(layout.work)
        result = plan(layout, active=active, daemon=daemon, now=now)
        result['mode'] = 'apply' if apply else 'dry-run'
        result['deleted'] = []
        preserved(result)
        if apply:
            try:
                for c in result['candidates']:
                    # Recheck writers and metadata before EACH mutation.
                    paths, running = process_state() if active is None else (active, daemon)
                    if busy(Path(c['path']), paths) or (c['kind'] == 'build' and busy(Path(c['root']), paths)) or (c['kind'] == 'cache' and (running or cache_lock_reason(layout))):
                        raise RuntimeError('activity appeared before apply')
                    if git(layout.integrator, 'rev-parse', 'HEAD') != result['canonical_integrator']:
                        raise RuntimeError('canonical HEAD changed before apply')
                    if c['kind'] == 'build' and workspace_reason(Path(c['root']), layout, result['canonical_integrator'], paths):
                        raise RuntimeError('workspace protection changed before apply')
                    preserved(result)
                    remove(c)
                    result['deleted'].append(c['path'])
            except Exception as exc:
                result['errors'].append(str(exc))
        preserved(result)
        after = os.statvfs(layout.work)
        result['actual_freed_bytes_statvfs'] = (after.f_bavail - before.f_bavail) * after.f_frsize
        result['preservation_verified'] = True
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='delete generated candidates (requires separate operator approval)')
    parser.add_argument('--lock-wait', type=int, choices=range(0, 301), default=0, metavar='0..300')
    parser.add_argument('--output', type=Path, required=True, help='new private JSON evidence file outside approved cleanup roots')
    args = parser.parse_args()
    layout = Layout()
    output = args.output.absolute()
    if any(output.is_relative_to(p) for p in (layout.apk, layout.cache)) or output.exists():
        parser.error('output must be a NEW evidence file outside APK/cache roots')
    output.parent.mkdir(parents=True, exist_ok=True)
    try:
        result = run(layout, apply=args.apply, wait=args.lock_wait)
    except Exception as exc:
        result = {'mode': 'apply' if args.apply else 'dry-run', 'errors': [str(exc)]}
    with output.open('x', encoding='utf-8') as stream:
        os.chmod(output, 0o600)
        json.dump(result, stream, indent=2, sort_keys=True)
        stream.write('\n')
    print(json.dumps({k: v for k, v in result.items() if k in
                      ('mode', 'counts', 'candidate_bytes', 'actual_freed_bytes_statvfs', 'errors', 'preservation_verified')}))
    return bool(result['errors'])


if __name__ == '__main__':
    sys.exit(main())
