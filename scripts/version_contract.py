"""Strict delivery history, separate from legacy cycle/tag archive compatibility."""
import json
import re
from datetime import datetime
from pathlib import Path


def semver(value):
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", value):
        raise ValueError("strict MAJOR.MINOR.PATCH required")
    return tuple(map(int, value.split(".")))


def validate(root):
    from scripts.release_cycle import parse_gradle_version
    records = json.loads((root / "CHANGELOG.json").read_text())
    previous = None
    for item in records:
        version = semver(item["version"])
        stamp = datetime.fromisoformat(item["date_msk"])
        assert stamp.utcoffset().total_seconds() == 10800, "date must be MSK"
        assert item["title"].strip() and 2 <= len(item["changes"]) <= 3
        assert all(line.strip() for line in item["changes"])
        if previous:
            assert version > semver(previous["version"]), "version must increase"
            assert item["versionCode"] == previous["versionCode"] + 1, "code must increase by one"
        previous = item
    assert previous is not None
    assert parse_gradle_version((root / "app/build.gradle.kts").read_text()) == (previous["versionCode"], previous["version"]), "latest changelog must match Gradle"


if __name__ == "__main__":
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    validate(Path(__file__).resolve().parents[1])
    print("DELIVERY VERSION VERIFIED")
