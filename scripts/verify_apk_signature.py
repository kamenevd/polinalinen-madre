#!/usr/bin/env python3
"""Verify APK signature and pin it to the expected release certificate."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
from pathlib import Path

FINGERPRINT_PATTERN = re.compile(r"^[0-9a-f]{64}$")

# apksigner labels a certificate block either by signer index ("Signer #1") or,
# once the APK carries a v3.1 block, by the SDK range that signer covers
# ("Signer (minSdkVersion=33, maxSdkVersion=2147483647)"), optionally annotated
# with "(dev release=true)". Both forms are accepted; every other SHA-256 line
# apksigner can emit -- public key digests, source stamps, lineage entries --
# must stay unmatched, so the whole line has to fit this grammar.
SIGNER_LABEL = r"""
    Signer
    (?:
        \s*\#\s*(?P<index>\d+)
        |
        \s*\(\s*minSdkVersion\s*=\s*\d+
            (?:\s*\(\s*dev\s+release\s*=\s*[A-Za-z]+\s*\))?
            \s*,\s*maxSdkVersion\s*=\s*\d+\s*\)
    )
"""
# Contiguous or colon grouped hex; normalize_fingerprint enforces the length.
HEX_DIGEST = r"[0-9a-fA-F]+(?::[0-9a-fA-F]+)*"
DIGEST_LINE = re.compile(
    rf"{SIGNER_LABEL}\s+certificate\s+SHA-?256\s+digest\s*:\s*(?P<digest>{HEX_DIGEST})",
    re.IGNORECASE | re.VERBOSE,
)
SIGNER_COUNT_LINE = re.compile(r"Number\s+of\s+signers\s*:\s*(?P<count>\d+)", re.IGNORECASE)
LONG_HEX_RUN = re.compile(r"[0-9a-fA-F]{16,}")


def digest_labels(lines: list[str]) -> list[str]:
    # Names the digest labels apksigner actually printed so an unknown output
    # format is diagnosable from the release log. Only the text left of the
    # colon is kept, and any hex run in it is masked, so no digest is logged.
    labels: list[str] = []
    for line in lines:
        head, separator, _ = line.partition(":")
        if not separator or "digest" not in head.lower():
            continue
        label = LONG_HEX_RUN.sub("<hex>", head.strip())
        if label and label not in labels:
            labels.append(label)
    return labels


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
    # The signer count is looked for anywhere in the output, so a stray count
    # can only ever tighten the check. A certificate digest, in contrast, has to
    # fill a line completely: apksigner appends nothing to those lines, so a
    # partial match could only come from text that is not the signer.
    counts = {int(count) for count in SIGNER_COUNT_LINE.findall(output)}
    lines = [raw_line.strip() for raw_line in output.splitlines()]
    digests = [match for match in (DIGEST_LINE.fullmatch(line) for line in lines) if match]
    if len(counts) > 1:
        raise ValueError("contradictory apksigner signer count lines")
    if counts and 1 not in counts:
        raise ValueError("expected exactly one APK signer")
    if not digests:
        labels = digest_labels(lines)
        seen = f"; apksigner printed: {', '.join(labels)}" if labels else ""
        raise ValueError(f"expected exactly one APK signer certificate digest{seen}")
    if any(match["index"] is not None and int(match["index"]) != 1 for match in digests):
        raise ValueError("unexpected additional APK signer certificate digest")
    fingerprints = {normalize_fingerprint(match["digest"]) for match in digests}
    if len(fingerprints) != 1:
        raise ValueError("conflicting APK signer certificate digests")
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
