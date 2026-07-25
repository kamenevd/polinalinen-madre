#!/usr/bin/env python3
"""Fail closed before Gradle packages a release artifact."""

from __future__ import annotations

import os
from collections.abc import Mapping
from pathlib import Path

REQUIRED = ("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")


def validate_signing_environment(environment: Mapping[str, str]) -> list[str]:
    missing = [name for name in REQUIRED if not environment.get(name, "").strip()]
    if missing:
        return ["missing release signing inputs: " + ", ".join(missing)]
    keystore = Path(environment["KEYSTORE_PATH"])
    if not keystore.is_file():
        return ["release keystore does not exist"]
    return []


def main() -> int:
    errors = validate_signing_environment(os.environ)
    if errors:
        raise SystemExit("; ".join(errors))
    print("RELEASE SIGNING INPUTS VALID")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
