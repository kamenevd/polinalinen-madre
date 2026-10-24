#!/usr/bin/env python3
"""Parent-only, fenced file transaction. NEVER starts/stops/restarts a service.

backup/check/stage are non-activating. switch/rollback require externally
sanctioned quiescence; serviceguard refusal must never be bypassed.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
import os
import sqlite3
import subprocess
import tarfile
from pathlib import Path

SERVICE = "madre-alice-voice.service"
PRODUCTION = Path("/opt/madre-alice-voice")
DB = Path("/var/lib/madre-alice-voice/voice.sqlite")
PROTECTED = [Path(p) for p in (
    "/etc/systemd/system/madre-alice-voice.service",
    "/etc/systemd/system/madre-alice-voice.service.d",
    "/etc/systemd/system/madre-alice.service",
    "/root/.secrets/madre-alice-voice.env",
    "/root/.secrets/madre-alice-auth.env",
    "/root/.secrets/madre-yandex-oauth.json",
    "/root/.secrets/madre-yandex-server.env",
    "/opt/madre-alice",
)]


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def files(paths):
    result = {}
    for path in paths:
        path = Path(path)
        if path.is_file():
            result[str(path)] = digest(path)
        elif path.is_dir():
            for child in sorted(path.rglob("*")):
                if child.is_file() and "__pycache__" not in child.parts:
                    result[str(child)] = digest(child)
    return result


def save_json(path, value):
    raw = json.dumps(value, indent=2, sort_keys=True).encode() + b"\n"
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as handle:
        handle.write(raw)
        handle.flush()
        os.fsync(handle.fileno())


def backup(output, root=PRODUCTION, db=DB, protected=PROTECTED):
    output, root, db = Path(output), Path(root), Path(db)
    # Never overwrite a previous backup. Destination is a dedicated 0700 dir.
    output.mkdir(mode=0o700, parents=False, exist_ok=False)
    current = (root / "current").resolve(strict=True)
    metadata = {"current": str(current), "root": str(root), "db": str(db),
                "protected_roots": [str(p) for p in protected],
                "protected": files(protected), "source": files([current])}
    archive = output / "code-config.tar"
    with tarfile.open(archive, "x") as tar:
        for name in [*metadata["protected"], *metadata["source"]]:
            tar.add(name, arcname=name.lstrip("/"), recursive=False)
    os.chmod(archive, 0o600)
    # SQLite online backup, NOT a copy of the live main file without its WAL.
    src = sqlite3.connect(db.resolve().as_uri() + "?mode=ro", uri=True, timeout=2)
    dest = sqlite3.connect(output / "voice.sqlite")
    try:
        src.backup(dest)
        if dest.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise RuntimeError("backup integrity failed")
        metadata["schema"] = dest.execute("PRAGMA user_version").fetchone()[0]
    finally:
        src.close()
        dest.close()
    os.chmod(output / "voice.sqlite", 0o600)
    metadata["archive_sha256"] = digest(archive)
    metadata["db_sha256"] = digest(output / "voice.sqlite")
    save_json(output / "manifest.json", metadata)
    verify_backup(output)
    return metadata


def verify_backup(output):
    output = Path(output)
    meta = json.loads((output / "manifest.json").read_text())
    for name, key in (("code-config.tar", "archive_sha256"), ("voice.sqlite", "db_sha256")):
        if digest(output / name) != meta[key]:
            raise RuntimeError("backup checksum mismatch")
    expected = {**meta["protected"], **meta["source"]}
    actual = {}
    with tarfile.open(output / "code-config.tar", "r") as tar:
        for member in tar:
            if not member.isfile():
                raise RuntimeError("unexpected archive entry")
            actual["/" + member.name] = hashlib.sha256(tar.extractfile(member).read()).hexdigest()
    if actual != expected:
        raise RuntimeError("archive contents mismatch")
    with sqlite3.connect((output / "voice.sqlite").as_uri() + "?mode=ro", uri=True) as conn:
        if conn.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise RuntimeError("backup integrity failed")
    return meta


def stage(repo, sha, root=PRODUCTION):
    repo, root = Path(repo).resolve(), Path(root)
    def git(*args):
        return subprocess.check_output(["git", "-C", str(repo), *args])
    if git("rev-parse", sha + "^{commit}").decode().strip() != sha:
        raise RuntimeError("full source SHA required")
    if git("status", "--porcelain"):
        raise RuntimeError("source worktree is not clean")
    destination = root / "releases" / sha
    if destination.exists():
        raise RuntimeError("release already exists")
    raw = git("archive", sha, "backend/alice")
    destination.mkdir(parents=True, mode=0o755)
    # Extract only tracked plain files/directories; forbid symlinks and traversal.
    with tarfile.open(fileobj=io.BytesIO(raw)) as tar:
        for member in tar:
            relative = Path(member.name)
            if relative.is_absolute() or ".." in relative.parts or not (member.isfile() or member.isdir()):
                raise RuntimeError("unsafe source archive")
            target = destination / relative
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True, mode=0o755)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(tar.extractfile(member).read())
                target.chmod(0o644)
    manifest = {"sha": sha, "files": files([destination / "backend/alice"])}
    save_json(destination / "source-manifest.json", manifest)
    return destination / "backend/alice"


def inactive():
    state = subprocess.check_output(["systemctl", "show", SERVICE, "-p", "ActiveState", "--value"], text=True).strip()
    return state == "inactive"


def validate_release(target):
    target = Path(target).resolve(strict=True)
    if target.parts[-2:] != ("backend", "alice"):
        raise RuntimeError("not a backend release")
    manifest = json.loads((target.parents[1] / "source-manifest.json").read_text())
    if files([target]) != manifest["files"]:
        raise RuntimeError("staged source drift")
    return target


def switch(output, target, *, rollback=False, is_inactive=inactive):
    meta = verify_backup(output)
    root = Path(meta["root"])
    current = root / "current"
    staged = validate_release(target)
    if not staged.is_relative_to(root / "releases"):
        raise RuntimeError("candidate outside owned releases")
    # Both directions are fenced on exact candidate source and protected config.
    # Caller must use a fresh backup after parent-owned config preparations.
    if files([Path(p) for p in meta["protected_roots"]]) != meta["protected"]:
        raise RuntimeError("protected file drift")
    original = Path(meta["current"])
    if files([original]) != meta["source"]:
        raise RuntimeError("original source drift")
    expected, desired = (staged, original) if rollback else (original, staged)
    if current.resolve(strict=True) != expected:
        raise RuntimeError("current pointer drift")
    if not is_inactive():
        raise RuntimeError("requires sanctioned inactive service; no lifecycle bypass")
    # Schema 2 is additive. KEEP latest DB and revocations on rollback; restoring
    # an earlier DB would resurrect revoked grants and erase post-deploy writes.
    with sqlite3.connect(Path(meta["db"]).resolve().as_uri() + "?mode=ro", uri=True) as conn:
        if conn.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise RuntimeError("current database corrupt; manual recovery required")
        if conn.execute("PRAGMA user_version").fetchone()[0] not in (1, 2):
            raise RuntimeError("unsupported rollback schema")
    pending = root / "current.yandex-a-pending"
    if pending.exists() or pending.is_symlink():
        raise RuntimeError("stale transaction pointer")
    pending.symlink_to(desired)
    os.replace(pending, current)
    fd = os.open(root, os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
    if current.resolve(strict=True) != desired:
        raise RuntimeError("pointer readback failed")
    return str(desired)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("backup", "check", "stage", "switch", "rollback"))
    parser.add_argument("--backup", type=Path)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--sha")
    parser.add_argument("--target", type=Path)
    args = parser.parse_args()
    if args.action == "backup":
        backup(args.backup)
    elif args.action == "check":
        verify_backup(args.backup)
    elif args.action == "stage":
        print(stage(args.source, args.sha))
    else:
        print(switch(args.backup, args.target, rollback=args.action == "rollback"))
    print("verified")


if __name__ == "__main__":
    main()
