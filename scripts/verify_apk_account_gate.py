#!/usr/bin/env python3
"""Read the actual QA APK's encoded BuildConfig without printing OAuth client identifiers."""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
import os
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

DESCRIPTOR = "Lcom/polinalinen/madre/BuildConfig;"
FIELDS = {"APPLICATION_ID", "YANDEX_ID", "YANDEX_CLIENT_ID", "VERSION_NAME", "VERSION_CODE", "DEBUG", "QA_DIAGNOSTICS", "BUILD_TYPE", "FAMILY_LINK_UI", "FAMILY_ROSTER_V2", "ALICE"}


def parse_build_config(dump: str) -> dict[str, object] | None:
    match = re.search(r"Class descriptor\s*:\s*'" + re.escape(DESCRIPTOR) + r"'(.*?)(?:\nClass #|\Z)", dump, re.S)
    if match is None:
        return None
    block = match.group(1).split("Instance fields")[0]
    fields: dict[str, object] = {}
    name = ""
    for line in block.splitlines():
        field = re.search(r"\bname\s*:\s*'([^']+)'", line)
        if field:
            name = field.group(1)
        value = re.search(r"\bvalue\s*:\s*(.+)$", line)
        if name in FIELDS and value:
            raw = value.group(1).strip()
            try:
                fields[name] = {"true": True, "false": False}[raw] if raw in {"true", "false"} else ast.literal_eval(raw)
            except (ValueError, SyntaxError):
                raise ValueError("APK BuildConfig field is not a supported literal") from None
    return fields


def validate_fields(fields: dict[str, object], expected_client: str) -> None:
    if fields.get("YANDEX_ID") is not True:
        raise ValueError("Actual QA APK account gate is not enabled")
    if fields.get("APPLICATION_ID") != "com.polinalinen.madre.qa":
        raise ValueError("Actual APK is not the separate QA package")
    if fields.get("ALICE") is not True:
        raise ValueError("Actual QA APK ALICE gate is not enabled")
    # Legacy debug remains an independent test target, not the final delivery QA.
    if fields.get("BUILD_TYPE") not in {"debug", "qa"}:
        raise ValueError("Actual APK has an unsupported QA build type")
    if fields["BUILD_TYPE"] == "qa":
        for name in ("FAMILY_LINK_UI", "FAMILY_ROSTER_V2"):
            if fields.get(name) is not True:
                raise ValueError("Actual delivery QA APK " + name + " gate is not enabled")
    if not re.fullmatch(r"[0-9a-fA-F]{32}", expected_client):
        raise ValueError("Expected registered public client must come from MADRE_YANDEX_CLIENT_ID")
    if fields.get("YANDEX_CLIENT_ID") != expected_client:
        raise ValueError("Actual APK public client does not match the approved CI client")


def read_apk_fields(apk: Path) -> dict[str, object]:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("Android SDK location is required for the real DEX verifier")
    tool = Path(sdk) / "build-tools" / os.environ.get("MADRE_BUILD_TOOLS_VERSION", "36.0.0") / "dexdump"
    if not tool.is_file():
        raise ValueError("Pinned build-tools dexdump is missing")
    scratch = Path(os.environ.get("TMPDIR") or os.environ.get("RUNNER_TEMP") or (Path.home() / ".cache/madre-verification"))
    scratch.mkdir(parents=True, exist_ok=True)
    found: list[dict[str, object]] = []
    with tempfile.TemporaryDirectory(prefix="madre-apk-fields-", dir=scratch) as directory, zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            data = archive.read(name)
            if DESCRIPTOR.encode() not in data:
                continue
            path = Path(directory) / name
            path.write_bytes(data)
            result = subprocess.run([str(tool), "-f", str(path)], check=True, capture_output=True, text=True, timeout=60)
            fields = parse_build_config(result.stdout)
            if fields is not None:
                found.append(fields)
    if len(found) != 1:
        raise ValueError("Expected exactly one actual app BuildConfig definition")
    return found[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    try:
        fields = read_apk_fields(args.apk)
        validate_fields(fields, os.environ.get("MADRE_YANDEX_CLIENT_ID", ""))
        print(json.dumps({
            "package": fields["APPLICATION_ID"], "version": fields.get("VERSION_NAME"),
            "versionCode": fields.get("VERSION_CODE"), "YANDEX_ID": True,
            "ALICE": fields["ALICE"], "BUILD_TYPE": fields["BUILD_TYPE"],
            "FAMILY_LINK_UI": fields.get("FAMILY_LINK_UI"),
            "FAMILY_ROSTER_V2": fields.get("FAMILY_ROSTER_V2"),
            "public_client_matches_approved_CI_client": True,
            "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        }))
        return 0
    except (ValueError, OSError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        # Never echo DEX contents, client values, environment or tool streams.
        print(f"APK ACCOUNT GATE VERIFICATION FAILED: {type(error).__name__}: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
