#!/usr/bin/env python3
"""Select one trusted, explicitly marked final QA head in its existing PR run."""
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MARKER = "Madre-Final-QA: 7.1.24(77)"
BRANCH = "feat/razvorot-7-0-1"


def selected(event, repository, head_repository, branch, message, gradle):
    # PR-only: no second push trigger/dispatch, forks or unmarked later microsteps.
    if not (event == "pull_request" and repository and head_repository == repository
            and branch == BRANCH and MARKER in message.splitlines()):
        return False
    codes = re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", gradle, re.M)
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', gradle, re.M)
    if codes != ["77"] or names != ["7.1.24"]:
        raise ValueError("Marked final QA head must be exactly 7.1.24(77)")
    return True


def main():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    head = event.get("pull_request", {}).get("head", {})
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if os.environ["GITHUB_EVENT_NAME"] == "pull_request" and sha != head.get("sha"):
        raise ValueError("Selector checkout must be the exact PR head, never its merge commit")
    message = subprocess.check_output(["git", "log", "-1", "--format=%B"], cwd=ROOT, text=True)
    value = selected(os.environ["GITHUB_EVENT_NAME"], os.environ["GITHUB_REPOSITORY"],
                     head.get("repo", {}).get("full_name"), head.get("ref"), message,
                     (ROOT / "app/build.gradle.kts").read_text())
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        output.write("final_qa=" + str(value).lower() + "\n")
    print("Final QA exact-head smoke selected: " + str(value).lower())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
