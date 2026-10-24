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
            "compileDebugKotlin",
            "20 key native frames",
            "verifyRoborazziDebug",
            "assembleDebug",
            "bash scripts/run_instrumented_acceptance.sh",
            "retention-days:",
        ):
            self.assertIn(required, text)
        # The pinned emulator action executes each input line in its own shell.
        # Follow the exact invoked helper, not a decoy command in a YAML comment.
        script = self.read("scripts/run_instrumented_acceptance.sh")
        self.assertRegex(script, r"(?m)^\s*\./gradlew connectedDebugAndroidTest ")
        self.assertIn("./gradlew :app:connectedDebugAndroidTest", script)
        self.assertIn("com.polinalinen.madre.FullscreenInstrumentedTest,com.polinalinen.madre.NavigationSmokeTest", script)
        self.assertIn('|| result=$?', script)
        self.assertIn('exit "$result"', script)
        self.assertIn("adb shell cmd overlay enable com.android.internal.display.cutout.emulation.tall", script)

    def test_pr_keeps_protected_smoke_name_but_skips_native_work(self):
        text = self.read(".github/workflows/quality-gates.yml")
        smoke = text.split("  instrumented-smoke:\n", 1)[1]
        self.assertIn("name: Instrumented smoke", smoke)
        self.assertIn("if: github.event_name == 'workflow_dispatch' || github.event_name == 'schedule'", smoke)
        quality = text.split("  android-quality:\n", 1)[1].split("  lint-report:\n", 1)[0]
        self.assertNotIn("needs:", quality)
        critical = next(line for line in quality.splitlines() if "run: ./gradlew compileDebugKotlin" in line)
        self.assertIn("testDebugUnitTest assembleDebug", critical)
        self.assertNotIn("lintDebug", critical)
        self.assertNotIn("verifyRoborazziDebug", critical)
        self.assertIn("-Pmadre.extendedVerification=true", quality)
        self.assertIn("github.event_name == 'workflow_dispatch' && inputs.capture_baseline", quality)

    def test_lint_is_a_separate_nonblocking_report(self):
        text = self.read(".github/workflows/quality-gates.yml")
        lint = text.split("  lint-report:\n", 1)[1].split("  instrumented-smoke:\n", 1)[0]
        self.assertIn("continue-on-error: true", lint)
        self.assertIn("lintDebug", lint)
        self.assertIn("lint-reports", lint)

    def test_real_qa_runtime_condition_executes_under_the_pinned_actions_posix_shell(self):
        import subprocess
        text=self.read(".github/workflows/quality-gates.yml")
        line=next(line.strip() for line in text.splitlines() if 'then bash scripts/run_qa_runtime_acceptance.sh' in line)
        line=line.replace('bash scripts/run_qa_runtime_acceptance.sh','printf QA_RUNTIME_CALLED')
        for value,expected in [('true','QA_RUNTIME_CALLED'),('false','')]:
            result=subprocess.run(['sh','-c',line.replace('${{ inputs.bake_numbers_native }}',value)],capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)
            self.assertEqual(expected,result.stdout)

    def test_qa_signing_is_verified_before_upload_without_a_fallback(self):
        text = self.read(".github/workflows/quality-gates.yml")
        quality = text.split("  android-quality:\n", 1)[1].split("  lint-report:\n", 1)[0]
        restore = quality.index("run: python3 scripts/restore_qa_signing.py\n")
        build = quality.index("run: ./gradlew compileDebugKotlin")
        verify = quality.index("id: verify_qa_signature")
        upload = quality.index("name: Upload verified debug APK first")
        self.assertLess(restore, build)
        self.assertLess(build, verify)
        self.assertLess(verify, upload)
        gate = quality[quality.index("name: Verify stable QA APK identity"):quality.index("name: Extended visual verification")]
        self.assertNotIn("continue-on-error", gate)
        self.assertNotIn("if:", gate)
        self.assertIn("scripts/verify_apk_signature.py app/build/outputs/apk/debug/app-debug.apk", gate)
        self.assertIn("if: success() && steps.verify_qa_signature.outcome == 'success'", quality[upload:])
        self.assertIn("path: app/build/outputs/apk/debug/app-debug.apk", quality[upload:])
        self.assertIn('MADRE_BUILD_TOOLS_VERSION: "36.0.0"', quality)
        self.assertIn('sdkmanager "build-tools;${MADRE_BUILD_TOOLS_VERSION}"', quality)
        for name in ("KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD", "SIGNING_CERT_SHA256"):
            self.assertIn("secrets.MADRE_QA_" + name, quality)
        self.assertIn("MADRE_YANDEX_CLIENT_ID: ${{ secrets.MADRE_YANDEX_CLIENT_ID }}", quality)
        self.assertNotIn("secrets.MADRE_KEYSTORE_", text)
        self.assertIn("if: always()\n        run: python3 scripts/restore_qa_signing.py --cleanup", quality)

    def test_debug_signing_has_its_own_identity_and_keeps_qa_isolation(self):
        text = self.read("app/build.gradle.kts")
        debug = text.split("        debug {", 1)[1].split("        release {", 1)[0]
        self.assertIn('applicationIdSuffix = ".qa"', debug)
        self.assertIn('signingConfig = signingConfigs.findByName("qa")', debug)
        self.assertNotIn('getByName("debug")', debug)
        for name in ("QA_KEYSTORE_PATH", "QA_KEYSTORE_PASSWORD", "QA_KEY_ALIAS", "QA_KEY_PASSWORD"):
            self.assertIn('System.getenv("' + name + '")', text)
        self.assertIn("QA signing inputs are incomplete", text)
        self.assertIn('applicationId = "com.polinalinen.madre"', text)

    def test_style_boundary_is_a_real_checked_scanner_not_a_placeholder(self):
        text = self.read("app/src/test/java/com/polinalinen/madre/ui/StyleBoundaryTest.kt")
        self.assertIn('"scripts/style_boundary.py", "--check"', text)
        self.assertIn("assertEquals(output, 0, process.waitFor())", text)
        self.assertNotIn("@Ignore", text)

    def test_rc_exclusions_are_exact_visual_classes_not_domain_packages(self):
        text = self.read("app/build.gradle.kts")
        excluded = text.split("val extendedOnlyTests = listOf(\n", 1)[1].split("\n)", 1)[0]
        for name in re.findall(r'"([^"]+)"', excluded):
            self.assertNotIn("*", name)
            self.assertTrue((ROOT / "app/src/test/java" / (name.replace(".", "/") + ".kt")).is_file(), name)
            self.assertTrue(name.endswith(("GoldenTest", "ExtendedRenderTest", "MaterialExtendedTest", "StaticFontCompatibilityTest")), name)
        self.assertTrue((ROOT / "app/src/androidTest/java/com/polinalinen/madre/data/db/MigrationTest.kt").is_file())

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
