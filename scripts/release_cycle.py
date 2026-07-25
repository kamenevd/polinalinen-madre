#!/usr/bin/env python3
"""Prepare and verify reproducible Madre release artifacts."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
from pathlib import Path
from typing import Any

PRE_RELEASE_GATES = ("plan", "tdd", "build", "review", "visual", "runtime")
SAFE_VERSION = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]*$")


class ReleaseError(ValueError):
    """A release contract violation."""


def parse_gradle_version(text: str) -> tuple[int, str]:
    code_matches = re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", text, re.MULTILINE)
    name_matches = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', text, re.MULTILINE)
    if len(code_matches) != 1 or len(name_matches) != 1:
        raise ReleaseError("expected exactly one versionCode/versionName pair")
    return int(code_matches[0]), name_matches[0]


def prepare_gradle_version(text: str, cycle_version: str) -> tuple[str, int]:
    artifact_names(cycle_version)
    current_code, _ = parse_gradle_version(text)
    new_code = current_code + 1
    updated, code_count = re.subn(
        r"(^\s*versionCode\s*=\s*)\d+(\s*$)",
        rf"\g<1>{new_code}\g<2>",
        text,
        flags=re.MULTILINE,
    )
    updated, name_count = re.subn(
        r'(^\s*versionName\s*=\s*)"[^"]+"(\s*$)',
        rf'\g<1>"{cycle_version}"\g<2>',
        updated,
        flags=re.MULTILINE,
    )
    if code_count != 1 or name_count != 1:
        raise ReleaseError("refusing ambiguous Gradle version update")
    return updated, new_code


def artifact_names(version: str) -> dict[str, str]:
    if not SAFE_VERSION.fullmatch(version):
        raise ReleaseError(f"unsafe version for artifact name: {version!r}")
    prefix = f"madre-v{version}"
    return {
        "apk": f"{prefix}.apk",
        "source": f"{prefix}-src.tar.gz",
        "manifest": f"{prefix}-manifest.json",
    }


def ensure_releasable(state: dict[str, Any]) -> None:
    if state.get("cycle", {}).get("stage") != "releasable":
        raise ReleaseError("cycle stage must be releasable")
    gates = state.get("gates", {})
    for gate in PRE_RELEASE_GATES:
        if gates.get(gate, {}).get("status") != "pass":
            raise ReleaseError(f"{gate} gate must pass before packaging")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _artifact_record(path: Path) -> dict[str, Any]:
    return {"file": path.name, "bytes": path.stat().st_size, "sha256": sha256_file(path)}


def make_manifest(version: str, version_code: int, apk: Path, source: Path) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "version": version,
        "version_code": version_code,
        "artifacts": {
            "apk": _artifact_record(apk),
            "source": _artifact_record(source),
        },
    }


def verify_manifest(manifest: dict[str, Any], root: Path) -> list[str]:
    errors: list[str] = []
    for kind in ("apk", "source"):
        record = manifest.get("artifacts", {}).get(kind, {})
        filename = record.get("file")
        if not isinstance(filename, str) or Path(filename).name != filename:
            errors.append(f"{kind}: invalid filename")
            continue
        path = root / filename
        if not path.is_file():
            errors.append(f"{kind}: missing file {filename}")
            continue
        if path.stat().st_size != record.get("bytes"):
            errors.append(f"{kind}: size mismatch")
        if sha256_file(path) != record.get("sha256"):
            errors.append(f"{kind}: sha256 mismatch")
    return errors


def _tracked_files(repo_root: Path) -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-z"], cwd=repo_root, check=True, capture_output=True
    )
    return [Path(item.decode("utf-8")) for item in result.stdout.split(b"\0") if item]


def safe_archive_relative(relative: Path) -> Path:
    if relative.is_absolute() or not relative.parts or any(part in {"", ".", ".."} for part in relative.parts):
        raise ReleaseError(f"unsafe path in tracked files: {relative}")
    return relative


def create_reproducible_source_archive(repo_root: Path, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".tmp", dir=output.parent)
    os.close(fd)
    try:
        with open(tmp_name, "wb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode="w") as archive:
                    for relative in sorted(_tracked_files(repo_root), key=lambda p: p.as_posix()):
                        relative = safe_archive_relative(relative)
                        source = repo_root / relative
                        try:
                            source.resolve(strict=True).relative_to(repo_root.resolve(strict=True))
                        except (OSError, ValueError) as exc:
                            raise ReleaseError(f"tracked file escapes repository: {relative}") from exc
                        if source.is_symlink():
                            raise ReleaseError(f"tracked symlinks are not allowed in source archive: {relative}")
                        if not source.is_file():
                            continue
                        info = archive.gettarinfo(str(source), arcname=f"madre/{relative.as_posix()}")
                        info.uid = info.gid = 0
                        info.uname = info.gname = ""
                        info.mtime = 0
                        with source.open("rb") as handle:
                            archive.addfile(info, handle)
        os.replace(tmp_name, output)
    except BaseException:
        try:
            os.unlink(tmp_name)
        except FileNotFoundError:
            pass
        raise


def atomic_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_name, path)
    except BaseException:
        try:
            os.unlink(tmp_name)
        except FileNotFoundError:
            pass
        raise


def package(repo_root: Path, state_path: Path, apk_path: Path, output_dir: Path) -> Path:
    state = json.loads(state_path.read_text(encoding="utf-8"))
    ensure_releasable(state)
    version = state["cycle"]["version"]
    names = artifact_names(version)
    gradle_path = repo_root / "app/build.gradle.kts"
    version_code, gradle_version = parse_gradle_version(gradle_path.read_text(encoding="utf-8"))
    if gradle_version != version:
        raise ReleaseError(f"Gradle version {gradle_version} does not match cycle {version}")
    if not apk_path.is_file():
        raise ReleaseError(f"APK does not exist: {apk_path}")
    output_dir.mkdir(parents=True, exist_ok=True)
    apk_output = output_dir / names["apk"]
    source_output = output_dir / names["source"]
    shutil.copyfile(apk_path, apk_output)
    create_reproducible_source_archive(repo_root, source_output)
    manifest = make_manifest(version, version_code, apk_output, source_output)
    manifest_path = output_dir / names["manifest"]
    atomic_json(manifest_path, manifest)
    errors = verify_manifest(manifest, output_dir)
    if errors:
        raise ReleaseError("; ".join(errors))
    return manifest_path


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prepare = sub.add_parser("prepare-version")
    prepare.add_argument("--state", type=Path, default=Path("workflow/CYCLE.yaml"))
    prepare.add_argument("--gradle", type=Path, default=Path("app/build.gradle.kts"))
    plan = sub.add_parser("plan")
    plan.add_argument("--state", type=Path, default=Path("workflow/CYCLE.yaml"))
    pack = sub.add_parser("package")
    pack.add_argument("--state", type=Path, default=Path("workflow/CYCLE.yaml"))
    pack.add_argument("--apk", type=Path, required=True)
    pack.add_argument("--output", type=Path, default=Path("dist"))
    verify = sub.add_parser("verify")
    verify.add_argument("manifest", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "prepare-version":
        state = json.loads(args.state.read_text(encoding="utf-8"))
        updated, code = prepare_gradle_version(args.gradle.read_text(encoding="utf-8"), state["cycle"]["version"])
        args.gradle.write_text(updated, encoding="utf-8")
        print(f"PREPARED versionCode={code} versionName={state['cycle']['version']}")
        return 0
    if args.command == "plan":
        state = json.loads(args.state.read_text(encoding="utf-8"))
        ensure_releasable(state)
        print(json.dumps(artifact_names(state["cycle"]["version"]), indent=2))
        return 0
    if args.command == "package":
        repo_root = args.state.resolve().parent.parent
        manifest = package(repo_root, args.state.resolve(), args.apk.resolve(), args.output.resolve())
        print(manifest)
        return 0
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    errors = verify_manifest(manifest, args.manifest.parent)
    if errors:
        print("\n".join(errors))
        return 1
    print("RELEASE ARTIFACTS VERIFIED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
