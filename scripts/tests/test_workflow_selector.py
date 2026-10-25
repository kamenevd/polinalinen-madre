"""Execute the workflow's real selector/guard, without Gradle, secrets or devices.

The indentation readers intentionally accept only this workflow's scalar job/step
layout. Conditions are parsed as expressions, not checked by substring counts.
No third-party YAML dependency is needed by the existing unittest CI command.
"""
import ast
import itertools
import json
import os
from pathlib import Path
import re
import subprocess
import textwrap
from types import SimpleNamespace
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
ACCEPTANCE = (
    "capture_baseline", "fixes_7013_acceptance", "fixes_7014_acceptance",
    "fixes_7014_instrumented", "fixes_7015_huawei", "perf_compare",
    "bake_numbers_native", "exact_qa_timing", "family_link_render",
)
GUARD = "Reject conflicting manual selectors before any build or acceptance work"
EXTENDED = "Extended visual verification (manual, nightly and RC)"
FIXES = "FIXES frozen-head native acceptance (manual only)"
BASELINE = "Native412 comparison from immutable60985a8 app sources"
CRITICAL = "Compile, domain tests, style boundary, 20 key native frames and QA APK"


def sections(text, pattern):
    matches = list(re.finditer(pattern, text, re.MULTILINE))
    return {match.group(1): text[match.end():matches[i + 1].start() if i + 1 < len(matches) else len(text)]
            for i, match in enumerate(matches)}


def scalar(text, key, indent):
    match = re.search(r"^" + " " * indent + re.escape(key) + r": (.+)$", text, re.MULTILINE)
    return match.group(1) if match else None


def step_blocks(job):
    return sections(job, r"^      - (?:name|uses): (.+)\n")


def condition(expression, event, flags, success=True, outcomes=None):
    # Execute the actual workflow if-expression. Reject syntax outside the
    # deliberately small boolean-expression subset used by these selectors.
    if expression is None:
        return success
    source = expression.replace("&&", " and ").replace("||", " or ")
    source = re.sub(r"!(?!=)", " not ", source).strip()
    tree = ast.parse(source, mode="eval")
    allowed = (ast.Expression, ast.BoolOp, ast.And, ast.Or, ast.UnaryOp, ast.Not,
               ast.Compare, ast.Eq, ast.NotEq, ast.Name, ast.Load, ast.Attribute,
               ast.Constant, ast.Call)
    for node in ast.walk(tree):
        if not isinstance(node, allowed):
            raise AssertionError(f"unsupported workflow condition: {ast.dump(node)}")
        if isinstance(node, ast.Call) and not (
                isinstance(node.func, ast.Name) and node.func.id in ("success", "always")
                and not node.args and not node.keywords):
            raise AssertionError("unexpected workflow condition call")
    inputs = SimpleNamespace(**dict.fromkeys(("critical_only", *ACCEPTANCE), False) | flags)
    steps = SimpleNamespace(**{name: SimpleNamespace(outcome=value)
                              for name, value in (outcomes or {}).items()})
    return bool(eval(compile(tree, "<actual-workflow-condition>", "eval"),
                     {"__builtins__": {}},
                     {"github": SimpleNamespace(event_name=event), "inputs": inputs,
                      "steps": steps, "success": lambda: success, "always": lambda: True}))


class WorkflowSelectorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = (ROOT / ".github/workflows/quality-gates.yml").read_text()
        cls.jobs = sections(cls.text.split("\njobs:\n", 1)[1], r"^  ([a-z0-9-]+):\n")
        cls.steps = step_blocks(cls.jobs["android-acceptance"])
        cls.short_steps = step_blocks(cls.jobs["android-quality"])
        cls.guard_step = step_blocks(cls.jobs["workflow-contract"])[GUARD]
        cls.guard_shell = textwrap.dedent(cls.guard_step.split("        run: |\n", 1)[1]).strip()
        match = re.fullmatch(r"python3 - <<'PY'\n(.*)\nPY", cls.guard_shell, re.DOTALL)
        if not match:
            raise AssertionError("guard must remain a directly executable Python shell step")
        cls.guard_code = compile(match.group(1), "<actual-workflow-input-guard>", "exec")

    def execute_guard(self, flags):
        with patch.dict(os.environ, {"WORKFLOW_INPUTS_JSON": json.dumps(flags)}):
            exec(self.guard_code, {})

    def test_dispatch_inputs_are_explicit_boolean_false_by_default(self):
        inputs = self.text.split("    inputs:\n", 1)[1].split("  schedule:\n", 1)[0]
        definitions = sections(inputs, r"^      ([a-z0-9_]+):\n")
        self.assertEqual({"critical_only", *ACCEPTANCE}, set(definitions))
        for name, definition in definitions.items():
            with self.subTest(name=name):
                self.assertEqual("boolean", scalar(definition, "type", 8))
                self.assertEqual("false", scalar(definition, "default", 8))
        self.assertEqual("${{ toJSON(inputs) }}", scalar(self.guard_step, "WORKFLOW_INPUTS_JSON", 10))

    def test_actual_shell_guard_accepts_defaults_and_critical_and_rejects_mixed(self):
        for flags, valid in [(None, True), ({}, True), ({"critical_only": True}, True),
                             ({"critical_only": True, "perf_compare": True}, False)]:
            with self.subTest(flags=flags):
                result = subprocess.run(["bash", "-e", "-c", self.guard_shell],
                                        env=os.environ | {"WORKFLOW_INPUTS_JSON": json.dumps(flags)},
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(valid, result.returncode == 0, result.stderr)
                if not valid:
                    self.assertIn("critical_only cannot be mixed with: perf_compare", result.stderr)

    def test_all_acceptance_combinations_fail_closed_only_when_mixed(self):
        for values in itertools.product((False, True), repeat=len(ACCEPTANCE)):
            flags = dict(zip(ACCEPTANCE, values))
            # The new family-only renderer cannot silently mask another native acceptance selector.
            family_mixed = flags["family_link_render"] and any(enabled for name, enabled in flags.items() if name != "family_link_render")
            for selected in (flags, flags | {"critical_only": False}):
                if family_mixed:
                    with self.assertRaises(SystemExit):
                        self.execute_guard(selected)
                else:
                    self.execute_guard(selected)
            with self.subTest(flags=flags):
                if any(values):
                    with self.assertRaises(SystemExit) as failure:
                        self.execute_guard(flags | {"critical_only": True})
                    for name, enabled in flags.items():
                        if enabled:
                            self.assertIn(name, str(failure.exception))
                else:
                    self.execute_guard(flags | {"critical_only": True})
        # Future acceptance flags must not silently bypass the guard either.
        with self.assertRaises(SystemExit):
            self.execute_guard({"critical_only": True, "future_acceptance": True})

    def test_invalid_selection_blocks_every_work_job_before_setup(self):
        contract_steps = step_blocks(self.jobs["workflow-contract"])
        self.assertEqual(GUARD, next(iter(contract_steps)))
        self.assertIsNone(scalar(self.guard_step, "if", 8))
        self.assertIsNone(scalar(self.guard_step, "continue-on-error", 8))
        self.assertIsNone(scalar(self.jobs["workflow-contract"], "continue-on-error", 4))
        expected = {"android-quality": "workflow-contract", "android-acceptance": "workflow-contract", "lint-report": "workflow-contract",
                    "instrumented-smoke": "android-acceptance", "required-7015-performance": "workflow-contract"}
        self.assertEqual({"workflow-contract", *expected}, set(self.jobs))
        for job, dependency in expected.items():
            self.assertEqual(dependency, scalar(self.jobs[job], "needs", 4))
            expression = scalar(self.jobs[job], "if", 4) or ""
            self.assertNotIn("always()", expression)
            self.assertNotIn("failure()", expression)

    def test_short_gate_is_independent_of_optional_acceptance(self):
        flags = {"critical_only": True}
        self.execute_guard(flags)
        short = self.jobs["android-quality"]
        self.assertIn("ref: ${{ github.event.pull_request.head.sha || github.sha }}", short)
        self.assertIsNone(scalar(short, "if", 4))
        self.assertEqual("workflow-contract", scalar(short, "needs", 4))
        self.assertIsNone(scalar(short, "continue-on-error", 4))
        command = scalar(self.short_steps["Short APK gate - compile, executed units and ordinary QA"], "run", 8)
        self.assertEqual("./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:testDebugUnitTest :app:assembleDebug :app:assembleQa -Pmadre.shortApkGate=true --no-daemon --stacktrace --no-configuration-cache", command)
        for forbidden in ("qaRegression", "verifyRoborazzi", "AndroidTest", "emulator", "RenderTest"):
            self.assertNotIn(forbidden, short)
        for event in ("pull_request", "push", "workflow_dispatch", "schedule"):
            self.assertFalse(condition(scalar(self.jobs["android-acceptance"], "if", 4), event, flags))
        self.assertFalse(condition(scalar(self.jobs["android-acceptance"], "if", 4), "pull_request", {}))
        self.assertTrue(condition(scalar(self.jobs["android-acceptance"], "if", 4), "workflow_dispatch", {}))
        for name in ("Verify stable QA APK identity before any APK upload",
                     "Verify actual QA APK account flag and approved public client without exposing values",
                     "Verify ordinary nondebug R8 QA and permanent certificate"):
            self.assertIsNone(scalar(self.short_steps[name], "if", 8))
            self.assertIsNone(scalar(self.short_steps[name], "continue-on-error", 8))
        upload = self.short_steps["Upload verified short QA and provenance"]
        for outcome in ("failure", "skipped", "cancelled"):
            self.assertFalse(condition(scalar(upload, "if", 8), "pull_request", {}, outcomes={"verify_real_qa": outcome}))
        self.assertTrue(condition(scalar(upload, "if", 8), "pull_request", {}, outcomes={"verify_real_qa": "success"}))

    def test_short_selector_excludes_only_exact_visual_classes_and_keeps_critical_units(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        names = []
        for variable in ("shortApkVisualTests", "extendedOnlyTests"):
            body = gradle.split(f"val {variable} = listOf(\n", 1)[1].split("\n)", 1)[0]
            names += re.findall(r'"([^"]+)"', body)
        names.append("com.polinalinen.madre.familyui.FamilyLinkRenderTest")
        for name in names:
            self.assertNotIn("*", name)
            self.assertTrue(name.endswith(("RenderTest", "GoldenTest", "MaterialExtendedTest", "StaticFontCompatibilityTest")), name)
            self.assertTrue((ROOT / "app/src/test/java" / (name.replace(".", "/") + ".kt")).is_file())
        for source in (ROOT / "app/src/test/java").rglob("*Test.kt"):
            qualified = str(source.relative_to(ROOT / "app/src/test/java")).removesuffix(".kt").replace("/", ".")
            if any(marker in source.read_text() for marker in ("captureRoboImage", "captureScreenRoboImage", "captureToImage")):
                self.assertIn(qualified, names)
            if any(marker in source.stem for marker in ("Migration", "AccountRecovery", "Controller", "DataSurvival", "Privacy", "Persistence", "Planning")):
                self.assertNotIn(qualified, names)
        self.assertIn('if (shortApkGate) it.filter {', gradle)
        self.assertIn('.forEach { name -> excludeTestsMatching(name) }', gradle)

    def test_default_manual_nightly_pr_push_and_rc_selection_is_preserved(self):
        for values in itertools.product((False, True), repeat=len(ACCEPTANCE)):
            flags = dict(zip(ACCEPTANCE, values)) | {"critical_only": False}
            for event in ("workflow_dispatch", "schedule", "pull_request", "push"):
                manual = event == "workflow_dispatch"
                extended = event in ("workflow_dispatch", "schedule") and not any(
                    flags[name] for name in ACCEPTANCE if name not in ("capture_baseline", "perf_compare"))
                expected_steps = {EXTENDED: extended,
                                  FIXES: manual and (flags["fixes_7013_acceptance"] or flags["fixes_7014_acceptance"]),
                                  BASELINE: manual and flags["capture_baseline"]}
                for name, expected in expected_steps.items():
                    with self.subTest(event=event, flags=flags, step=name):
                        self.assertEqual(expected, condition(scalar(self.steps[name], "if", 8), event, flags))
                self.assertEqual(event in ("workflow_dispatch", "schedule") and not flags["family_link_render"],
                                 condition(scalar(self.jobs["instrumented-smoke"], "if", 4), event, flags))
                self.assertEqual(manual and flags["fixes_7015_huawei"],
                                 condition(scalar(self.jobs["required-7015-performance"], "if", 4), event, flags))
        # RC still requires a real smoke success; critical_only's skip is not PASS.
        from scripts import verify_github_release_context
        self.assertIn("Instrumented smoke", verify_github_release_context.REQUIRED_CHECKS)
        for event in ("workflow_dispatch", "schedule"):
            self.assertTrue(condition(scalar(self.steps[EXTENDED], "if", 8), event, {}))
            self.assertTrue(condition(scalar(self.jobs["instrumented-smoke"], "if", 4), event, {}))

    def test_family_render_selector_is_optional_and_does_not_replace_core_or_run_native(self):
        step = self.steps["Optional family K Compose renders (manual only)"]
        for event in ("pull_request", "push", "schedule", "workflow_dispatch"):
            for enabled in (False, True):
                flags = {"family_link_render": enabled}
                self.assertEqual(event == "workflow_dispatch" and enabled,
                                 condition(scalar(step, "if", 8), event, flags))
        self.assertIn("critical-before-family-render", step)
        self.assertIn("-Pmadre.familyLinkRender=true", step)
        self.assertIn("--tests 'com.polinalinen.madre.familyui.FamilyLinkRenderTest'", step)
        self.assertEqual("quality-${{ github.workflow }}-${{ github.head_ref || github.ref_name }}", scalar(self.text.split("concurrency:\n", 1)[1], "group", 2))
        self.assertIn("cancel-in-progress: true", self.text)
        self.assertFalse(condition(scalar(self.jobs["instrumented-smoke"], "if", 4),
                                   "workflow_dispatch", {"family_link_render": True}))
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('if (!familyLinkRender) it.filter { excludeTestsMatching("com.polinalinen.madre.familyui.FamilyLinkRenderTest") }', gradle)

    def test_critical_artifacts_require_successful_verification_not_skips_or_cancellation(self):
        artifact_steps = {
            "Upload actual verified release-like QA and exact build provenance": ("verify_real_qa",),
            "Upload explicitly test-only R8 persistence target, never deliver it": ("verify_real_qa",),
            "Upload same-certificate minified QA instrumentation": ("verify_real_qa",),
            "Upload verified debug APK first": ("verify_qa_signature", "verify_qa_account_gate"),
        }
        flags = {"critical_only": True}
        for name, verifiers in artifact_steps.items():
            expression = scalar(self.steps[name], "if", 8)
            outcomes = dict.fromkeys(verifiers, "success")
            self.assertTrue(condition(expression, "workflow_dispatch", flags, outcomes=outcomes))
            self.assertEqual("error", scalar(self.steps[name], "if-no-files-found", 10))
            self.assertIsNone(scalar(self.steps[name], "continue-on-error", 8))
            self.assertFalse(condition(expression, "workflow_dispatch", flags, success=False, outcomes=outcomes))
            for verifier in verifiers:
                for outcome in ("failure", "skipped", "cancelled"):
                    self.assertFalse(condition(expression, "workflow_dispatch", flags,
                                               outcomes=outcomes | {verifier: outcome}))
        png = self.steps["Upload key native PNG"]
        self.assertIsNone(scalar(png, "if", 8))
        self.assertEqual("error", scalar(png, "if-no-files-found", 10))
        self.assertTrue(condition(None, "workflow_dispatch", flags))
        self.assertFalse(condition(None, "workflow_dispatch", flags, success=False))


if __name__ == "__main__":
    unittest.main()
