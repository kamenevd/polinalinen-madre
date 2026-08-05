#!/usr/bin/env python3
"""Verify APK signature and pin it to the expected release certificate."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
from collections.abc import Mapping
from pathlib import Path

FINGERPRINT_PATTERN = re.compile(r"^[0-9a-f]{64}$")

# apksigner labels a certificate block by signer index ("Signer #1"), by the SDK
# range that signer covers once the APK carries a v3.1 block ("Signer
# (minSdkVersion=33, maxSdkVersion=2147483647)", optionally annotated with "(dev
# release=true)"), and -- from Build Tools 35 onwards -- by the signing scheme
# that produced the block ("V3.0 Signer: certificate SHA-256 digest: ..."), where
# the label itself ends with a colon and may still carry an index or SDK range.
# Every other SHA-256 line apksigner can emit -- public key digests, source
# stamps, lineage entries -- must stay unmatched, so the whole line has to fit
# this grammar and at least one qualifier (scheme, index or SDK range) has to be
# present: a bare "Signer" is not a label this verifier claims to understand.
SCHEME_PREFIX = r"(?P<scheme>V1|V2|V3\.0|V3\.1)\s+"
SIGNER_INDEX = r"\s*\#\s*(?P<index>\d+)"
SDK_RANGE = r"""
    \s*(?P<range>
        \(\s*minSdkVersion\s*=\s*\d+
            (?:\s*\(\s*dev\s+release\s*=\s*[A-Za-z]+\s*\))?
            \s*,\s*maxSdkVersion\s*=\s*\d+\s*\)
    )
"""
SIGNER_LABEL = rf"(?:{SCHEME_PREFIX})? Signer (?:{SIGNER_INDEX})? (?:{SDK_RANGE})? \s*:?"
# Contiguous or colon grouped hex; normalize_fingerprint enforces the length.
HEX_DIGEST = r"[0-9a-fA-F]+(?::[0-9a-fA-F]+)*"
DIGEST_LINE = re.compile(
    rf"{SIGNER_LABEL}\s+certificate\s+SHA-?256\s+digest\s*:\s*(?P<digest>{HEX_DIGEST})",
    re.IGNORECASE | re.VERBOSE,
)
SIGNER_COUNT_LINE = re.compile(r"Number\s+of\s+signers\s*:\s*(?P<count>\d+)", re.IGNORECASE)
DIGEST_MARKER = re.compile(r"digest\s*:", re.IGNORECASE)
LONG_HEX_RUN = re.compile(r"[0-9a-fA-F]{16,}")


def digest_labels(lines: list[str]) -> list[str]:
    # Names the digest labels apksigner actually printed so an unknown output
    # format is diagnosable from the release log. The label is everything left of
    # the LAST "digest:" marker -- scheme labels such as "V3.0 Signer:" contain a
    # colon of their own, so splitting at the first one reported nothing at all --
    # and any hex run inside it is masked, so no digest or key material is logged.
    labels: list[str] = []
    for line in lines:
        markers = list(DIGEST_MARKER.finditer(line))
        if not markers:
            continue
        label = LONG_HEX_RUN.sub("<hex>", line[: markers[-1].end()].rstrip(": \t").strip())
        if label and label not in labels:
            labels.append(label)
    return labels


def signer_digest_matches(lines: list[str]) -> list[re.Match[str]]:
    # A certificate digest has to fill a line completely: apksigner appends
    # nothing to those lines, so a partial match could only come from text that is
    # not the signer.
    matches = (DIGEST_LINE.fullmatch(line) for line in lines)
    return [
        match
        for match in matches
        if match and (match["scheme"] or match["index"] or match["range"])
    ]


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
    # can only ever tighten the check.
    counts = {int(count) for count in SIGNER_COUNT_LINE.findall(output)}
    lines = [raw_line.strip() for raw_line in output.splitlines()]
    digests = signer_digest_matches(lines)
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


# The release APK is signed by AGP 8.2.0, whose default Build Tools are 34.0.0;
# the verifier reads the signature back with exactly that apksigner instead of
# whatever the runner happens to have installed. Picking the lexically latest
# build-tools directory silently changed apksigner -- and its output format --
# whenever the GitHub image shipped a newer SDK, which is how a correctly signed
# release failed verification. The release workflow passes this same version in
# MADRE_BUILD_TOOLS_VERSION and installs it if the runner lacks it.
PINNED_BUILD_TOOLS_VERSION = "34.0.0"
BUILD_TOOLS_VERSION_PATTERN = re.compile(r"^\d+\.\d+\.\d+(?:-rc\d+)?$")


def pinned_build_tools_version(environ: Mapping[str, str] | None = None) -> str:
    environ = os.environ if environ is None else environ
    version = (environ.get("MADRE_BUILD_TOOLS_VERSION") or "").strip() or PINNED_BUILD_TOOLS_VERSION
    # Also keeps the version out of path-traversal territory: it is joined onto
    # ANDROID_HOME below and must name a build-tools directory, nothing else.
    if not BUILD_TOOLS_VERSION_PATTERN.fullmatch(version):
        raise ValueError(f"invalid pinned build tools version: {version!r}")
    return version


def pinned_apksigner(android_home: Path, version: str) -> Path:
    if str(android_home) in ("", "."):
        raise ValueError("ANDROID_HOME is not set")
    candidate = android_home / "build-tools" / version / "apksigner"
    if not candidate.is_file():
        raise ValueError(f"apksigner for pinned build tools {version} not found under ANDROID_HOME")
    return candidate


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
        signer = pinned_apksigner(android_home, pinned_build_tools_version())
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
