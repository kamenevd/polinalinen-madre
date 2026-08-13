import re
import unittest
from pathlib import Path

from scripts import verify_apk_signature, verify_github_release_context


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
            "verifyRoborazziDebug",
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
            "MADRE_SIGNING_CERT_SHA256",
            "checks: read",
            "verify_github_release_context.py",
            "verify_apk_signature.py",
            "git merge-base --is-ancestor",
            "assembleRelease",
            "scripts/release_cycle.py package",
            "softprops/action-gh-release",
        ):
            self.assertIn(required, text)
        self.assertNotIn("assembleDebug", text)

    def test_release_workflow_checks_main_and_quality_in_separate_named_steps(self):
        text = self.read(".github/workflows/release.yml")
        for step in (
            "name: Fetch main",
            "name: Verify tagged commit is on main",
            "name: Verify required quality checks",
        ):
            self.assertIn(step, text)

    def test_release_job_timeout_covers_the_check_wait_plus_the_build(self):
        # The gate may legitimately wait out a running quality run; the job must
        # not be the thing that gives up, least of all mid-Gradle.
        text = self.read(".github/workflows/release.yml")
        job_timeout = re.findall(r"^\s*timeout-minutes:\s*(\d+)", text, re.MULTILINE)
        self.assertEqual(1, len(job_timeout), "release workflow must set one job timeout")
        wait_minutes = verify_github_release_context.DEFAULT_TIMEOUT_SECONDS / 60
        # 35 minutes is what "Test and build signed release" needed before the
        # wait existed, so that is the build budget the wait has to sit on top of.
        self.assertGreaterEqual(int(job_timeout[0]), wait_minutes + 35)

    def test_release_gate_waits_but_never_below_a_usable_floor(self):
        source = self.read("scripts/verify_github_release_context.py")
        self.assertIn("wait_for_required_checks", source)
        self.assertEqual(300.0, verify_github_release_context.MINIMUM_TIMEOUT_SECONDS)
        self.assertEqual(
            ("Workflow integrity", "Android quality", "Instrumented smoke"),
            verify_github_release_context.REQUIRED_CHECKS,
        )

    def test_release_workflow_passes_the_exact_tag_being_released(self):
        # Without the current tag the packager compares the build against its own
        # tag and rejects its own versionCode.
        text = self.read(".github/workflows/release.yml")
        package = re.findall(r"^.*scripts/release_cycle\.py package.*$", text, re.MULTILINE)
        self.assertEqual(1, len(package), "release workflow must package exactly once")
        self.assertIn('--current-tag "${GITHUB_REF_NAME}"', package[0])

    def test_release_workflow_pins_the_build_tools_the_verifier_expects(self):
        text = self.read(".github/workflows/release.yml")
        pinned = re.findall(r'^\s*MADRE_BUILD_TOOLS_VERSION:\s*"([^"]+)"', text, re.MULTILINE)
        self.assertEqual(1, len(pinned), "release workflow must pin build tools exactly once")
        self.assertEqual(verify_apk_signature.PINNED_BUILD_TOOLS_VERSION, pinned[0])
        # The runner image is not trusted to ship the pinned build tools already.
        self.assertIn("sdkmanager", text)
        self.assertIn('"build-tools;${MADRE_BUILD_TOOLS_VERSION}"', text)

    def test_verifier_never_selects_build_tools_by_scanning_the_sdk(self):
        source = self.read("scripts/verify_apk_signature.py")
        self.assertNotIn("glob(", source)
        self.assertIn("PINNED_BUILD_TOOLS_VERSION", source)

    def test_release_build_never_falls_back_to_debug_signing(self):
        text = self.read("app/build.gradle.kts")
        release_block = text[text.index("release {") : text.index("compileOptions")]
        self.assertNotIn('signingConfigs.getByName("debug")', release_block)
        self.assertIn('it.name == "packageRelease"', text)
        self.assertIn("verifyReleaseSigning", text)
        self.assertIn("scripts/check_release_signing.py", text)
        self.assertIn("Release signing inputs are incomplete", text)

    def test_dependabot_covers_gradle_and_actions(self):
        text = self.read(".github/dependabot.yml")
        self.assertIn('package-ecosystem: "gradle"', text)
        self.assertIn('package-ecosystem: "github-actions"', text)

    def test_pr_template_requires_evidence(self):
        text = self.read(".github/pull_request_template.md")
        for gate in ("PLAN", "TDD", "BUILD", "REVIEW", "VISUAL", "RUNTIME"):
            self.assertIn(gate, text)
        self.assertIn("workflow/CYCLE.yaml", text)

    def test_all_github_actions_are_pinned_to_commit_sha(self):
        for workflow in ("quality-gates.yml", "release.yml"):
            text = self.read(f".github/workflows/{workflow}")
            uses = re.findall(r"^\s*-?\s*uses:\s*([^\s#]+)", text, re.MULTILINE)
            self.assertTrue(uses, workflow)
            for action in uses:
                self.assertRegex(action, r"^[^@]+@[0-9a-f]{40}$", f"unpinned action: {action}")


if __name__ == "__main__":
    unittest.main()
