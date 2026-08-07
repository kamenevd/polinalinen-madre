import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def parse_gradle_version(text: str) -> tuple[int, str]:
    code_matches = re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", text, re.MULTILINE)
    name_matches = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', text, re.MULTILINE)
    if len(code_matches) != 1 or len(name_matches) != 1:
        raise AssertionError("expected exactly one versionCode/versionName pair")
    return int(code_matches[0]), name_matches[0]


class WorkflowVersionContractTests(unittest.TestCase):
    def read(self, relative: str) -> str:
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_cycle_branch_matches_number_and_kind(self):
        manifest = json.loads(self.read("workflow/CYCLE.yaml"))
        c = manifest["cycle"]
        kind = c["kind"]
        number = c["number"]
        branch = c["branch"]
        self.assertIn(kind, {"feature", "maintenance"})
        prefix = "maintenance" if kind == "maintenance" else "cycle"
        self.assertEqual(branch, f"{prefix}/{number}")

    def test_design_documents_every_cycle_up_to_current(self):
        current = json.loads(self.read("workflow/CYCLE.yaml"))["cycle"]["number"]
        cycles = [
            int(v)
            for v in re.findall(r"^## Cycle (\d+)\b", self.read("DESIGN-V4.md"), re.MULTILINE)
        ]
        self.assertEqual(list(range(1, current + 1)), cycles)

    def test_gradle_version_matches_cycle_manifest_when_release_ready(self):
        """Fail-closed coupling: once release gate is in play, names must match.

        Before prepare-version on a maintenance branch, gradle may still show the
        last published name while CYCLE.yaml already names the target. That gap is
        allowed only while release.status is pending and stage is not releasable.
        """
        manifest = json.loads(self.read("workflow/CYCLE.yaml"))
        code, name = parse_gradle_version(self.read("app/build.gradle.kts"))
        target = manifest["cycle"]["version"]
        stage = manifest["cycle"]["stage"]
        release_status = manifest.get("gates", {}).get("release", {}).get("status", "pending")
        self.assertIsInstance(code, int)
        self.assertGreaterEqual(code, 1)
        if release_status == "pass" or stage in {"releasable", "released"}:
            self.assertEqual(name, target)
        else:
            # Target must be declared; gradle stays untouched until prepare-version.
            self.assertTrue(re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._-]*", target))
            self.assertNotEqual(target, "", "cycle version must be set")


if __name__ == "__main__":
    unittest.main()
