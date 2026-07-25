#!/usr/bin/env python3
"""Verify that the tagged commit passed all required GitHub quality checks."""

from __future__ import annotations

import json
import os
import urllib.request
from collections.abc import Iterable

REQUIRED_CHECKS = ("Workflow integrity", "Android quality", "Instrumented smoke")


def verify_check_runs(check_runs: Iterable[dict], required: tuple[str, ...] = REQUIRED_CHECKS) -> list[str]:
    latest: dict[str, dict] = {}
    for run in check_runs:
        name = run.get("name")
        if name in required:
            current = latest.get(name)
            if current is None or run.get("completed_at", "") > current.get("completed_at", ""):
                latest[name] = run
    errors: list[str] = []
    for name in required:
        run = latest.get(name)
        if run is None:
            errors.append(f"missing required check: {name}")
        elif run.get("status") != "completed" or run.get("conclusion") != "success":
            errors.append(
                f"required check not successful: {name} status={run.get('status')} conclusion={run.get('conclusion')}"
            )
    return errors


def main() -> int:
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    sha = os.environ.get("GITHUB_SHA", "")
    token = os.environ.get("GITHUB_TOKEN", "")
    if not repository or not sha or not token:
        raise SystemExit("GitHub release context is incomplete")
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/commits/{sha}/check-runs?per_page=100",
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        payload = json.load(response)
    errors = verify_check_runs(payload.get("check_runs", []))
    if errors:
        raise SystemExit("; ".join(errors))
    print("REQUIRED GITHUB CHECKS VERIFIED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
