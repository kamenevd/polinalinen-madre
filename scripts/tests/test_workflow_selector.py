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
    "bake_numbers_native", "exact_qa_timing",
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
        cls.steps = step_blocks(cls.jobs["android-quality"])
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
            self.execute_guard(flags)  # unchanged default/manual acceptance combinations
            self.execute_guard(flags | {"critical_only": False})
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
        expected = {"android-quality": "workflow-contract", "lint-report": "workflow-contract",
                    "instrumented-smoke": "android-quality", "required-7015-performance": "workflow-contract"}
        self.assertEqual({"workflow-contract", *expected}, set(self.jobs))
        for job, dependency in expected.items():
            self.assertEqual(dependency, scalar(self.jobs[job], "needs", 4))
            expression = scalar(self.jobs[job], "if", 4) or ""
            self.assertNotIn("always()", expression)
            self.assertNotIn("failure()", expression)

    def test_critical_only_runs_the_unchanged_required_pr_path(self):
        flags = dict.fromkeys(ACCEPTANCE, False) | {"critical_only": True}
        self.execute_guard(flags)
        self.assertIsNone(scalar(self.jobs["android-quality"], "if", 4))
        self.assertIsNone(scalar(self.jobs["android-quality"], "continue-on-error", 4))
        self.assertEqual("30", scalar(self.jobs["android-quality"], "timeout-minutes", 4))
        self.assertEqual(
            "./gradlew compileDebugKotlin compileReleaseKotlin testDebugUnitTest assembleDebug assembleQa --no-daemon --stacktrace --no-configuration-cache",
            scalar(self.steps[CRITICAL], "run", 8))
        required = (
            "Restore private stable QA signing", CRITICAL,
            "Freeze ordinary delivery QA before building the test-only persistence ABI",
            "Build minified QA instrumentation separately without displacing debug domain and render tests",
            "Verify stable QA APK identity before any APK upload",
            "Verify actual QA APK account flag and approved public client without exposing values",
            "Verify real release-like QA binary, stable certificate, R8 mapping and packed ART",
        )
        for name in required:
            with self.subTest(step=name):
                self.assertIsNone(scalar(self.steps[name], "if", 8))
                self.assertIsNone(scalar(self.steps[name], "continue-on-error", 8))
                self.assertTrue(condition(scalar(self.steps[name], "if", 8), "workflow_dispatch", flags))
        for name in (EXTENDED, FIXES, BASELINE):
            self.assertFalse(condition(scalar(self.steps[name], "if", 8), "workflow_dispatch", flags))
        for name in ("instrumented-smoke", "required-7015-performance"):
            self.assertFalse(condition(scalar(self.jobs[name], "if", 4), "workflow_dispatch", flags))

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
                self.assertEqual(event in ("workflow_dispatch", "schedule"),
                                 condition(scalar(self.jobs["instrumented-smoke"], "if", 4), event, flags))
                self.assertEqual(manual and flags["fixes_7015_huawei"],
                                 condition(scalar(self.jobs["required-7015-performance"], "if", 4), event, flags))
        # RC still requires a real smoke success; critical_only's skip is not PASS.
        from scripts import verify_github_release_context
        self.assertIn("Instrumented smoke", verify_github_release_context.REQUIRED_CHECKS)
        for event in ("workflow_dispatch", "schedule"):
            self.assertTrue(condition(scalar(self.steps[EXTENDED], "if", 8), event, {}))
            self.assertTrue(condition(scalar(self.jobs["instrumented-smoke"], "if", 4), event, {}))

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
