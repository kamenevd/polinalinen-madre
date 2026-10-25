"""Fetch ONLY the pinned public test binary. Never an installer/service helper."""
from __future__ import annotations
import hashlib
import io
import os
from pathlib import Path
import sys
import time
import urllib.request
import zipfile

VERSION = "0.39.11"
SHA256 = "88370d5f6fa4820cd2414fa53c6e168d3dd0e33b7a7fd9ff914265492a7aa3b6"
URL = "https://github.com/pocketbase/pocketbase/releases/download/v0.39.11/pocketbase_0.39.11_linux_amd64.zip"


def fetch(target: Path) -> None:
    if target.exists():
        raise ValueError("refusing to overwrite an existing runtime binary")
    archive = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(URL, timeout=60) as response:
                archive = response.read(40_000_001)
            break
        except OSError:
            if attempt == 2:
                raise
            time.sleep(2)
    if archive is None:
        raise RuntimeError("public release download failed")
    if len(archive) > 40_000_000:
        raise ValueError("oversized public binary archive")
    data = zipfile.ZipFile(io.BytesIO(archive)).read("pocketbase")
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise ValueError("PocketBase release binary checksum mismatch")
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("xb") as stream:
        stream.write(data)
    os.chmod(target, 0o700)
    print(f"PocketBase {VERSION}; SHA256 {SHA256}")


if __name__ == "__main__":
    fetch(Path(sys.argv[1]).resolve())
