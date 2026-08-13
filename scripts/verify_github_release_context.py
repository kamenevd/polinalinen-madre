#!/usr/bin/env python3
"""Verify that the tagged commit passed all required GitHub quality checks.

A tag is normally pushed right after the merge, so the push-triggered quality
workflow is still running when the release workflow starts. A single snapshot of
the check-runs API therefore races that workflow and fails a release that would
have been green minutes later. This verifier waits instead — but only for checks
that have not finished yet, and only for a bounded time.

Fail-closed rules that must survive any future edit:
  * a required check that finished unsuccessfully fails the release at once,
    it is never waited out;
  * a check that is still queued or running wins over an older successful run of
    the same name, so a rerun in flight can never be released against its own
    stale green;
  * only GitHub Actions check runs count, so no third-party app can publish a
    check with a required name and open the gate;
  * running out of time is a failure, never a pass.
"""

from __future__ import annotations

import json
import os
import time
import urllib.request
from collections.abc import Callable, Iterable
from typing import NamedTuple

REQUIRED_CHECKS = ("Workflow integrity", "Android quality", "Instrumented smoke")

# Only checks published by GitHub Actions itself are trusted.
GITHUB_ACTIONS_APP_SLUG = "github-actions"

# GitHub has added status names over the years (queued, in_progress, waiting,
# requested, pending), so anything that is not "completed" is treated as still
# running rather than matched against a list that will go stale.
COMPLETED_STATUS = "completed"

DEFAULT_POLL_SECONDS = 30.0
# "Instrumented smoke" is capped at 35 minutes by its own job timeout; waiting
# 40 covers it plus the time a runner spends queued.
DEFAULT_TIMEOUT_SECONDS = 2400.0
# Below this a wait cannot outlast even a fast quality run, and the gate would
# be back to failing releases on the race it exists to absorb.
MINIMUM_TIMEOUT_SECONDS = 300.0

API_TIMEOUT_SECONDS = 20


class ReleaseCheckError(RuntimeError):
    """The release must not proceed."""


class CheckVerdict(NamedTuple):
    """What the required checks look like in one snapshot of the API."""

    pending: list[str]
    failures: list[str]

    @property
    def green(self) -> bool:
        return not self.pending and not self.failures


def actions_check_runs(check_runs: Iterable[dict]) -> list[dict]:
    """Keep only check runs published by the GitHub Actions app."""
    kept = []
    for run in check_runs:
        app = run.get("app") or {}
        if isinstance(app, dict) and app.get("slug") == GITHUB_ACTIONS_APP_SLUG:
            kept.append(run)
    return kept


def _completed_at(run: dict) -> str:
    # A run that is still going has completed_at = null; comparing that against a
    # string raises, and sorting must not depend on it either.
    return run.get("completed_at") or ""


def evaluate_check_runs(
    check_runs: Iterable[dict],
    required: tuple[str, ...] = REQUIRED_CHECKS,
) -> CheckVerdict:
    """Classify each required check as pending, failed or successful."""
    runs = actions_check_runs(check_runs)
    pending: list[str] = []
    failures: list[str] = []
    for name in required:
        named = [run for run in runs if run.get("name") == name]
        if not named:
            pending.append(f"{name}: not reported yet")
            continue
        unfinished = [run for run in named if run.get("status") != COMPLETED_STATUS]
        if unfinished:
            # A rerun in flight outranks any older green of the same name.
            status = unfinished[0].get("status") or "unknown"
            pending.append(f"{name}: {status}")
            continue
        latest = max(named, key=_completed_at)
        if latest.get("conclusion") != "success":
            failures.append(f"{name}: completed with conclusion={latest.get('conclusion')}")
    return CheckVerdict(pending=pending, failures=failures)


def verify_check_runs(
    check_runs: Iterable[dict],
    required: tuple[str, ...] = REQUIRED_CHECKS,
) -> list[str]:
    """Every reason the required checks are not green right now."""
    verdict = evaluate_check_runs(check_runs, required)
    return verdict.failures + verdict.pending


def _positive_seconds(env: dict, key: str, default: float) -> float:
    raw = (env.get(key) or "").strip()
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError:
        raise ReleaseCheckError(f"{key} is not a number: {raw!r}") from None
    if value <= 0:
        raise ReleaseCheckError(f"{key} must be positive: {raw!r}")
    return value


def poll_settings(env: dict) -> tuple[float, float]:
    """Polling interval and total deadline, in seconds."""
    poll = _positive_seconds(env, "MADRE_RELEASE_CHECK_POLL_SECONDS", DEFAULT_POLL_SECONDS)
    timeout = _positive_seconds(env, "MADRE_RELEASE_CHECK_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS)
    if timeout < MINIMUM_TIMEOUT_SECONDS:
        raise ReleaseCheckError(
            "MADRE_RELEASE_CHECK_TIMEOUT_SECONDS must be at least "
            f"{MINIMUM_TIMEOUT_SECONDS:.0f} seconds, got {timeout:.0f}"
        )
    return poll, timeout


def wait_for_required_checks(
    fetch: Callable[[], Iterable[dict]],
    required: tuple[str, ...] = REQUIRED_CHECKS,
    poll_seconds: float = DEFAULT_POLL_SECONDS,
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
    sleep: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
    log: Callable[[str], None] = print,
) -> None:
    """Poll until every required check is successful, or fail.

    Raises ReleaseCheckError on a finished-but-unsuccessful check and on the
    deadline. Returns only when all required checks are successful.
    """
    deadline = clock() + timeout_seconds
    attempt = 0
    while True:
        attempt += 1
        verdict = evaluate_check_runs(fetch(), required)
        if verdict.failures:
            raise ReleaseCheckError(
                "required check did not pass: " + "; ".join(verdict.failures)
            )
        if verdict.green:
            log(f"attempt {attempt}: all required checks successful")
            return
        remaining = deadline - clock()
        log(
            f"attempt {attempt}: waiting for {'; '.join(verdict.pending)} "
            f"({remaining:.0f}s left)"
        )
        if remaining <= 0:
            raise ReleaseCheckError(
                f"timed out after {timeout_seconds:.0f}s waiting for required checks: "
                + "; ".join(verdict.pending)
            )
        sleep(min(poll_seconds, remaining))


def fetch_check_runs(repository: str, sha: str, token: str) -> list[dict]:
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/commits/{sha}/check-runs?per_page=100",
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request, timeout=API_TIMEOUT_SECONDS) as response:
        payload = json.load(response)
    return payload.get("check_runs", [])


def main() -> int:
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    sha = os.environ.get("GITHUB_SHA", "")
    token = os.environ.get("GITHUB_TOKEN", "")
    if not repository or not sha or not token:
        raise SystemExit("GitHub release context is incomplete")
    poll_seconds, timeout_seconds = poll_settings(os.environ)
    print(
        f"waiting up to {timeout_seconds:.0f}s (every {poll_seconds:.0f}s) for: "
        + ", ".join(REQUIRED_CHECKS)
    )
    try:
        wait_for_required_checks(
            lambda: fetch_check_runs(repository, sha, token),
            poll_seconds=poll_seconds,
            timeout_seconds=timeout_seconds,
        )
    except ReleaseCheckError as error:
        raise SystemExit(str(error)) from None
    print("REQUIRED GITHUB CHECKS VERIFIED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
