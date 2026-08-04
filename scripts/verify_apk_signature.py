#!/usr/bin/env python3
"""Verify APK signature and pin it to the expected release certificate."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
from pathlib import Path

FINGERPRINT_PATTERN = re.compile(r"^[0-9a-f]{64}$")
DIGEST_LINE = re.compile(r"Signer #(\d+) certificate SHA-256 digest:\s*([0-9a-fA-F:]+)")
SIGNER_COUNT_LINE = re.compile(r"Number of signers:\s*(\d+)")


def normalize_fingerprint(value: str) -> str:
    normalized = value.replace(":", "").strip().lower()
    if not FINGERPRINT_PATTERN.fullmatch(normalized):
        raise ValueError("certificate fingerprint must be 64 hexadecimal characters")
    return normalized


def combine_streams(stdout: str | None, stderr: str | None) -> str:
    # apksigner versions differ on whether the certificate block lands on
    # stdout or stderr, so both are analysed together.
    return "\n".join(part for part in (stdout or "", stderr or ""))


def signer_fingerprint_from_streams(stdout: str | None, stderr: str | None) -> str:
    return parse_signer_fingerprint(combine_streams(stdout, stderr))


def parse_signer_fingerprint(output: str) -> str:
    counts = [int(value) for value in SIGNER_COUNT_LINE.findall(output)]
    if len(set(counts)) > 1:
        raise ValueError("contradictory apksigner signer count lines")
    if counts and counts[0] != 1:
        raise ValueError("expected exactly one APK signer")
    matches = DIGEST_LINE.findall(output)
    if not matches:
        raise ValueError("expected exactly one APK signer certificate digest")
    if any(int(number) != 1 for number, _ in matches):
        raise ValueError("unexpected additional APK signer certificate digest")
    fingerprints = {normalize_fingerprint(digest) for _, digest in matches}
    if len(fingerprints) != 1:
        raise ValueError("conflicting Signer #1 certificate digests")
    return next(iter(fingerprints))


def latest_apksigner(android_home: Path) -> Path:
    candidates = sorted(android_home.glob("build-tools/*/apksigner"))
    if not candidates:
        raise ValueError("apksigner not found under ANDROID_HOME")
    return candidates[-1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--expected", default=os.environ.get("MADRE_SIGNING_CERT_SHA256", ""))
    args = parser.parse_args()
    if not args.apk.is_file() or "unsigned" in args.apk.name.lower():
        raise SystemExit("release APK is missing or explicitly unsigned")
    try:
        expected = normalize_fingerprint(args.expected)
        android_home = Path(os.environ.get("ANDROID_HOME", ""))
        signer = latest_apksigner(android_home)
        result = subprocess.run(
            [str(signer), "verify", "--verbose", "--print-certs", str(args.apk)],
            check=True,
            text=True,
            capture_output=True,
        )
        actual = signer_fingerprint_from_streams(result.stdout, result.stderr)
    except (ValueError, subprocess.CalledProcessError) as exc:
        raise SystemExit(str(exc)) from exc
    if actual != expected:
        raise SystemExit("APK signer certificate fingerprint mismatch")
    print("APK SIGNATURE AND CERTIFICATE VERIFIED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
