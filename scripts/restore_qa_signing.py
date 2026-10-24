#!/usr/bin/env python3
"""Restore only a QA keystore matching the required 7.0.6 update identity."""

from __future__ import annotations

import base64
import binascii
import os
import stat
import sys
from collections.abc import Mapping
from pathlib import Path

try:
    from scripts.verify_apk_signature import normalize_fingerprint
except ModuleNotFoundError:
    from verify_apk_signature import normalize_fingerprint

EXPECTED_QA_CERT_SHA256 = "DDE5FA55A95EA8E5310CB35A6C92A108316663564FD7950C7E5C664D5D80F639"
REQUIRED = (
    "QA_KEYSTORE_BASE64", "QA_KEYSTORE_PASSWORD", "QA_KEY_ALIAS",
    "QA_KEY_PASSWORD", "QA_SIGNING_CERT_SHA256",
)


def restore_qa_keystore(directory: Path, environment: Mapping[str, str]) -> Path:
    missing = [name for name in REQUIRED if not environment.get(name, "").strip()]
    if missing:
        raise ValueError("missing QA signing inputs: " + ", ".join(missing))
    if normalize_fingerprint(environment["QA_SIGNING_CERT_SHA256"]) != normalize_fingerprint(EXPECTED_QA_CERT_SHA256):
        raise ValueError("QA certificate pin does not match the approved stable identity")
    try:
        payload = base64.b64decode(environment["QA_KEYSTORE_BASE64"], validate=True)
    except (ValueError, binascii.Error) as exc:
        raise ValueError("invalid QA keystore encoding") from exc
    if not payload:
        raise ValueError("empty QA keystore")
    if directory.is_symlink():
        raise ValueError("QA signing directory must not be a symlink")
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    if directory.stat().st_uid != os.geteuid():
        raise ValueError("QA signing directory has a different owner")
    directory.chmod(0o700)
    destination = directory / "madre-qa.jks"
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(payload)
        stream.flush()
        os.fsync(stream.fileno())
    return destination


def main() -> int:
    temporary = os.environ.get("RUNNER_TEMP", "")
    if not temporary:
        raise SystemExit("RUNNER_TEMP is required for private QA signing")
    directory = Path(temporary) / "madre-qa-signing"
    if sys.argv[1:] == ["--cleanup"]:
        if directory.is_symlink():
            raise SystemExit("refusing a symlink QA signing directory")
        target = directory / "madre-qa.jks"
        if directory.exists() and directory.stat().st_uid == os.geteuid():
            target.unlink(missing_ok=True)
        return 0
    github_environment = os.environ.get("GITHUB_ENV", "")
    if not github_environment:
        raise SystemExit("GITHUB_ENV is required")
    try:
        target = restore_qa_keystore(directory, os.environ)
        if "\n" in str(target) or "\r" in str(target):
            raise ValueError("invalid QA signing path")
        with Path(github_environment).open("a", encoding="utf-8") as stream:
            stream.write(f"QA_KEYSTORE_PATH={target}\n")
    except (ValueError, OSError) as exc:
        # Never render payload, passwords or aliases, including on an error.
        raise SystemExit("QA signing restore failed: " + (str(exc) if isinstance(exc, ValueError) else "private file operation failed")) from None
    if stat.S_IMODE(target.stat().st_mode) != 0o600:
        raise SystemExit("QA keystore permissions are not private")
    print("QA SIGNING MATERIAL RESTORED PRIVATELY")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())