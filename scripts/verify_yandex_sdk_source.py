#!/usr/bin/env python3
"""Verify the narrow local security correction of pinned Yandex Auth SDK3.3.0."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

SOURCE_SHA256 = "3f731e1a48d3488a2daf15dea70d8c039122af4613465735e8a1203801bb35c5"
AAR_SHA256 = "0bc058b7c4856965b89d36c4379b7261346fb55513f44262a1f9485fb6a03556"
ALLOWED_CORRECTIONS = {
    "src/main/java/com/yandex/authsdk/internal/AuthSdkActivity.kt",
    "src/main/AndroidManifest.xml",
}


def verify(root: Path) -> dict:
    metadata = json.loads((root / "provenance.json").read_text())
    if metadata["source_sha256"] != SOURCE_SHA256 or metadata["aar_sha256"] != AAR_SHA256:
        raise ValueError("Upstream SDK3.3.0 pin differs")
    if set(metadata["modified_files"]) != ALLOWED_CORRECTIONS:
        raise ValueError("SDK correction scope differs")
    expected = metadata["original_files"]
    found = {
        str(path.relative_to(root)) for path in (root / "src/main").rglob("*")
        if path.is_file() and path.suffix in {".kt", ".java", ".xml"}
    }
    if found != set(expected):
        raise ValueError("SDK source inventory differs")
    unchanged = 0
    for relative, original_hash in expected.items():
        correction = metadata["modified_files"].get(relative)
        if correction and correction["original"] != original_hash:
            raise ValueError("Correction base differs")
        wanted = correction["corrected"] if correction else original_hash
        if hashlib.sha256((root / relative).read_bytes()).hexdigest() != wanted:
            raise ValueError(f"SDK source differs: {relative}")
        unchanged += int(correction is None)
    for name in ("LICENSE.txt", "UPSTREAM-NOTICE.txt", "NOTICE.txt"):
        if not (root / name).is_file() or not (root / "src/main/assets/third_party/yandex-authsdk-3.3.0" / name).is_file():
            raise ValueError("Missing SDK attribution")
    return {"status": "PASS", "scope": "SDK source integrity, NOT runtime custody", "unchanged_files": unchanged,
            "intentional_corrections": sorted(ALLOWED_CORRECTIONS)}


if __name__ == "__main__":
    print(json.dumps(verify(Path(__file__).resolve().parents[1] / "yandex-authsdk-330")))
