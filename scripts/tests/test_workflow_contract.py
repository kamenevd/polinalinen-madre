import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class WorkflowContractTests(unittest.TestCase):
    def read(self, relative):
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_legacy_build_workflow_is_removed(self):
        self.assertFalse((ROOT / ".github/workflows/build-apk.yml").exists())

    def test_quality_workflow_enforces_all_required_layers(self):
        text = self.read(".github/workflows/quality-gates.yml")
        for required in (
            "pull_request:",
            "contents: read",
            "concurrency:",
            "python3 -m unittest discover",
            "python3 scripts/cycle.py validate",
            "testDebugUnitTest",
            "lintDebug",
            "assembleDebug",
            "connectedDebugAndroidTest",
            "retention-days:",
        ):
            self.assertIn(required, text)

    def test_release_workflow_is_tag_only_and_fail_closed(self):
        text = self.read(".github/workflows/release.yml")
        for required in (
            "tags:",
            "contents: write",
            "MADRE_KEYSTORE_BASE64",
            "assembleRelease",
            "scripts/release_cycle.py package",
            "softprops/action-gh-release",
        ):
            self.assertIn(required, text)
        self.assertNotIn("assembleDebug", text)

    def test_release_build_never_falls_back_to_debug_signing(self):
        text = self.read("app/build.gradle.kts")
        release_block = text[text.index("release {") : text.index("compileOptions")]
        self.assertNotIn('signingConfigs.getByName("debug")', release_block)

    def test_dependabot_covers_gradle_and_actions(self):
        text = self.read(".github/dependabot.yml")
        self.assertIn('package-ecosystem: "gradle"', text)
        self.assertIn('package-ecosystem: "github-actions"', text)

    def test_pr_template_requires_evidence(self):
        text = self.read(".github/pull_request_template.md")
        for gate in ("PLAN", "TDD", "BUILD", "REVIEW", "VISUAL", "RUNTIME"):
            self.assertIn(gate, text)
        self.assertIn("workflow/CYCLE.yaml", text)


if __name__ == "__main__":
    unittest.main()
