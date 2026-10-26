"""Executable source/selector/transport fixtures; NOT an emulator acceptance receipt."""
import copy
import hashlib
import itertools
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from scripts import cycle, run_final_qa_smoke as smoke, select_final_qa as selector
from scripts.tests.test_workflow_selector import scalar, sections, step_blocks

ROOT = Path(__file__).resolve().parents[2]


class FinalQaReadinessTest(unittest.TestCase):
    def test_real_selector_cli_exact_head_marker_trust_and_version(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            (root / "app").mkdir()
            shutil.copy2(ROOT / "scripts/select_final_qa.py", root / "scripts/select_final_qa.py")
            (root / "app/build.gradle.kts").write_text('versionCode = 77\nversionName = "7.1.24"\n')
            subprocess.run(["git", "init", "-q", directory], check=True)
            for message in (selector.MARKER, "next ordinary microstep", "prefix " + selector.MARKER):
                subprocess.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                                "commit", "-q", "--allow-empty", "-m", message], cwd=root, check=True)
                sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
                for trusted, branch in itertools.product((False, True), (selector.BRANCH, "wip/final-qa-readiness")):
                    event = {"pull_request": {"head": {"sha": sha, "ref": branch,
                             "repo": {"full_name": "owner/repo" if trusted else "fork/repo"}}}}
                    (root / "event.json").write_text(json.dumps(event))
                    (root / "output").write_text("")
                    result = subprocess.run(["python3", root / "scripts/select_final_qa.py"], cwd=root,
                        env=os.environ | {"GITHUB_EVENT_PATH": str(root / "event.json"), "GITHUB_OUTPUT": str(root / "output"),
                            "GITHUB_EVENT_NAME": "pull_request", "GITHUB_REPOSITORY": "owner/repo"},
                        capture_output=True, text=True, timeout=10)
                    self.assertEqual(0, result.returncode, result.stderr)
                    enabled = message == selector.MARKER and trusted and branch == selector.BRANCH
                    self.assertEqual("final_qa=" + str(enabled).lower() + "\n", (root / "output").read_text())
            with self.assertRaisesRegex(ValueError, "exactly"):
                selector.selected("pull_request", "owner/repo", "owner/repo", selector.BRANCH,
                                  selector.MARKER, 'versionCode = 78\nversionName = "7.1.25"\n')
            for event in ("push", "workflow_dispatch", "schedule"):
                self.assertFalse(selector.selected(event, "owner/repo", "owner/repo", selector.BRANCH,
                                                   selector.MARKER, (ROOT / "app/build.gradle.kts").read_text()))
            # Checkout of a merge commit must fail before any smoke output is accepted.
            env = os.environ | {"GITHUB_EVENT_PATH": str(root / "event.json"), "GITHUB_OUTPUT": str(root / "output"),
                                "GITHUB_EVENT_NAME": "pull_request", "GITHUB_REPOSITORY": "owner/repo"}
            stale_event = json.loads((root / "event.json").read_text())
            stale_event["pull_request"]["head"]["sha"] = "0" * 40
            (root / "event.json").write_text(json.dumps(stale_event))
            result = subprocess.run(["python3", root / "scripts/select_final_qa.py"], cwd=root, env=env, capture_output=True)
            self.assertNotEqual(0, result.returncode)

    def test_protected_smoke_executes_real_condition_and_depends_only_on_short_producer(self):
        text = (ROOT / ".github/workflows/quality-gates.yml").read_text()
        jobs = sections(text.split("\njobs:\n", 1)[1], r"^  ([a-z0-9-]+):\n")
        job = jobs["instrumented-smoke"]
        self.assertEqual("Instrumented smoke", scalar(job, "name", 4))
        self.assertEqual("[workflow-contract, android-quality]", scalar(job, "needs", 4))
        expression = scalar(job, "if", 4)
        assert expression is not None
        condition = expression.replace("needs.workflow-contract", "needs.workflow_contract")
        condition = condition.replace("&&", " and ").replace("||", " or ").replace("!inputs", "not inputs")
        for event, selected, critical, family in itertools.product(
                ("push", "pull_request", "workflow_dispatch", "schedule"), ("false", "true"), (False, True), (False, True)):
            scope = {"github": SimpleNamespace(event_name=event),
                     "needs": SimpleNamespace(workflow_contract=SimpleNamespace(outputs=SimpleNamespace(final_qa=selected))),
                     "inputs": SimpleNamespace(critical_only=critical, family_link_render=family)}
            expected = (selected == "true" or event in ("workflow_dispatch", "schedule")) and not (critical or family)
            self.assertEqual(expected, eval(condition, {"__builtins__": {}}, scope))
        steps = step_blocks(job)
        download = steps["Download only the same-run verified ordinary QA"]
        self.assertEqual("madre-short-qa", scalar(download, "name", 10))
        for forbidden in ("run-id:", "github-token:", "continue-on-error", "assembleQa", "assembleDebug", "connectedBenchmark", "qaRegression=true"):
            self.assertNotIn(forbidden, job)
        self.assertIn("ref: ${{ github.event.pull_request.head.sha || github.sha }}", job)
        self.assertLess(job.index(":macrobenchmark:assembleBenchmark"), job.index("ReactiveCircus/android-emulator-runner@"))
        self.assertLess(job.index("run_final_qa_smoke.py --prepare"), job.index("ReactiveCircus/android-emulator-runner@"))
        native = steps["Smoke the exact ordinary signed QA, not debug or qaRegression"]
        self.assertEqual("python3 scripts/run_final_qa_smoke.py", scalar(native, "script", 10))
        self.assertEqual("always()", scalar(steps["Upload raw exact QA smoke evidence even on failure"], "if", 8))
        contract = jobs["workflow-contract"]
        self.assertIn("final_qa: ${{ steps.final_qa.outputs.final_qa }}", contract)
        self.assertIn("run: python3 scripts/select_final_qa.py", contract)

    def test_ordinary_provenance_is_fail_closed_for_every_field_and_flag(self):
        proof = dict(head="1" * 40, apk_sha256="2" * 64, mapping_sha256="3" * 64,
                     variant="qa", R8=True, debuggable=False)
        account = dict(apk_sha256=proof["apk_sha256"], BUILD_TYPE="qa",
                       YANDEX_ID=True, FAMILY_LINK_UI=True, FAMILY_ROSTER_V2=True, ALICE=True)
        smoke.verify_provenance(proof, account, proof["head"], proof["apk_sha256"], proof["mapping_sha256"])
        for container in ("proof", "account"):
            base = proof if container == "proof" else account
            for key in base:
                for replacement in (None, "wrong", 0, 1):
                    candidate = base | {key: replacement}
                    with self.subTest(container=container, key=key, value=replacement), self.assertRaises(ValueError):
                        smoke.verify_provenance(candidate if container == "proof" else proof,
                                                candidate if container == "account" else account,
                                                proof["head"], proof["apk_sha256"], proof["mapping_sha256"])

    def test_am_instrument_zero_exit_skips_empty_and_wrong_test_are_not_pass(self):
        # Literal runner-protocol fixture, not output from a real device run.
        good = ("INSTRUMENTATION_STATUS: test=actualR8SettingsProbeAndLocalFpsAreVisible\n"
                "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\n"
                "INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")
        smoke.validate_instrumentation(good)
        for bad in ("", good.replace("1 test", "0 tests"), good.replace("1 test", "2 tests"),
                    good.replace("actualR8SettingsProbeAndLocalFpsAreVisible", "wrongMethod"),
                    good.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
                    *[good + "\nINSTRUMENTATION_STATUS_CODE: " + str(code) for code in (-1, -2, -3, -4)],
                    good + "\nFAILURES!!!", good + "\nINSTRUMENTATION_FAILED", good + "\nshortMsg=crash"):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                smoke.validate_instrumentation(bad)

    def test_no_adb_without_ci_emulator_admission(self):
        for actions, ci, serial in itertools.product(("false", "true"), ("false", "true"), ("", "physical-serial", "emulator-5554")):
            if actions == ci == "true" and serial == "emulator-5554":
                continue
            with patch.dict(os.environ, {"GITHUB_ACTIONS": actions, "CI": ci, "ANDROID_SERIAL": serial}), patch.object(smoke, "run") as command:
                with self.assertRaisesRegex(ValueError, "CI emulator"):
                    smoke.runtime()
                command.assert_not_called()

    def test_failed_instrumentation_collects_native_frames_without_retry_or_masking(self):
        # Executable transport fixture only: no emulator/device is contacted.
        for collection_fails in (False, True):
            with self.subTest(collection_fails=collection_fails), tempfile.TemporaryDirectory() as directory:
                out = Path(directory)
                (out / "target.apk").write_bytes(b"fixture ordinary QA")
                (out / "driver.apk").write_bytes(b"fixture external driver")
                controls = dict(head="1" * 40, run_id="fixture-run", driver_package=smoke.DRIVER,
                                apk_sha256=smoke.sha(out / "target.apk"), driver_sha256=smoke.sha(out / "driver.apk"))
                (out / "controls.json").write_text(json.dumps(controls))
                commands = []

                def command(args, log=None, timeout=180):
                    args = [str(arg) for arg in args]
                    commands.append(args)
                    if args == ["git", "rev-parse", "HEAD"]:
                        return controls["head"]
                    self.assertEqual(["adb", "-s", "emulator-5554"], args[:3])
                    operation = args[3:]
                    if operation == ["get-serialno"]:
                        return "emulator-5554\n"
                    if operation == ["shell", "getprop", "ro.kernel.qemu"]:
                        return "1\n"
                    if operation == ["shell", "getprop", "ro.build.version.sdk"]:
                        return "35\n"
                    if operation == ["shell", "pm", "path", smoke.PACKAGE]:
                        return "package:/data/app/ordinary/base.apk\n"
                    if operation[:2] == ["pull", "/data/app/ordinary/base.apk"]:
                        Path(operation[2]).write_bytes((out / "target.apk").read_bytes())
                    if operation[:3] == ["shell", "am", "instrument"]:
                        self.assertIn(smoke.METHOD, operation)
                        return "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: Real settings entry missing\nINSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_CODE: -1\n"
                    if collection_fails and (operation[0] == "logcat" or operation[:2] == ["pull", "/sdcard/Download/madre-qa-p0"]):
                        raise RuntimeError("Fixture collection failure")
                    return ""

                with patch.object(smoke, "OUT", out), patch.object(smoke, "run", side_effect=command), patch.dict(
                        os.environ, {"GITHUB_ACTIONS": "true", "CI": "true", "ANDROID_SERIAL": "emulator-5554", "GITHUB_RUN_ID": "fixture-run"}):
                    with self.assertRaisesRegex(ValueError, "did not pass once without skips"):
                        smoke.runtime()
                self.assertEqual(1, sum(args[3:6] == ["shell", "am", "instrument"] for args in commands))
                self.assertTrue(any(args[3:5] == ["pull", "/sdcard/Download/madre-qa-p0"] for args in commands))
                self.assertTrue(any(args[3:] == ["shell", "dumpsys", "package", smoke.PACKAGE] for args in commands))
                self.assertFalse((out / "result.json").exists())

    def test_owner_driver_uses_real_l_settings_routes_without_retired_search(self):
        driver = (ROOT / "macrobenchmark/src/main/java/com/polinalinen/madre/macrobenchmark/QaOwnerAcceptance.kt").read_text()
        method = driver.split("@Test fun actualR8SettingsProbeAndLocalFpsAreVisible()", 1)[1]
        # Independently verified against the real production Android node export in
        # LSettingsQaDriverAccessibilityTest, not inferred from human-facing text.
        self.assertNotIn('By.clazz("android.widget.EditText")', method)
        self.assertNotIn('By.text("Диагностика QA")', method)
        routes = re.findall(r'clickAction\("([^"]+)"', method)
        self.assertEqual(["Настройки", "Уведомления и вибрация", "Проверить", "Назад",
                          "О приложении", "Для разработчиков", "Назад", "Внешний вид",
                          "Тема: Ночь", "Назад", "Назад"], routes)
        self.assertEqual(2, method.count('By.desc("Показывать FPS")'))
        for retained in ('assertEquals(0,info.flags and ApplicationInfo.FLAG_DEBUGGABLE)',
                         'By.textContains("hasVibrator:")', 'By.text("JankStats · текущее окно · 1 с")',
                         'By.textContains("кадров · UI p95")', 'By.text("Оглавление")',
                         '"qa-haptic-diagnostics"', '"qa-fps-settings"', '"qa-night-settings"', '"qa-night-toc"'):
            self.assertIn(retained, method)

    def test_owner_failure_export_executes_two_shell_commands_not_literal_semicolon(self):
        driver = (ROOT / "macrobenchmark/src/main/java/com/polinalinen/madre/macrobenchmark/QaOwnerAcceptance.kt").read_text()
        method = driver.split("@Test fun actualR8SettingsProbeAndLocalFpsAreVisible()", 1)[1]
        finalizer = method.rsplit("finally {", 1)[1]
        self.assertNotIn("; cp", finalizer)
        self.assertIn('executeShellCommand("mkdir -p /sdcard/Download/madre-qa-p0")', finalizer)
        self.assertIn('executeShellCommand("cp -R ${output.absolutePath}/. /sdcard/Download/madre-qa-p0/")', finalizer)
        # UiAutomation runs a command, not sh -c. Exercise the actual extracted
        # mkdir/cp strings with Runtime.exec-style whitespace tokenization locally.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, destination = root / "source", root / "export"
            source.mkdir()
            (source / "qa-owner-failure.xml").write_bytes(b"fixture raw XML")
            (source / "qa-owner-failure.png").write_bytes(b"fixture raw frame bytes, not a PNG")
            commands = re.findall(r'executeShellCommand\("([^"]+)"\)', finalizer)
            for command in commands:
                command = command.replace("/sdcard/Download/madre-qa-p0", str(destination)).replace("${output.absolutePath}", str(source))
                subprocess.run(command.split(), cwd=root, check=True, capture_output=True)
            self.assertEqual(sorted(p.name for p in source.iterdir()), sorted(p.name for p in destination.iterdir()))
            for path in source.iterdir():
                self.assertEqual(path.read_bytes(), (destination / path.name).read_bytes())

    def test_cycle29_is_new_pending_and_archive_is_verbatim(self):
        state = json.loads((ROOT / "workflow/CYCLE.yaml").read_text())
        self.assertEqual([], cycle.validate_state(state))
        self.assertEqual(29, state["cycle"]["number"])
        self.assertEqual("7.1.24", state["cycle"]["version"])
        self.assertEqual("planning", state["cycle"]["stage"])
        self.assertEqual({"pending"}, {gate["status"] for gate in state["gates"].values()})
        self.assertTrue(all(value is None for value in state["artifacts"].values()))
        # Independently verified verbatim bytes from
        # 477cf0b1d5ff4d9354b0231e7f57d2598e4cf287:workflow/CYCLE.yaml.
        # Pin integrity without requiring historic objects in depth-1 CI checkouts.
        archived = (ROOT / "workflow/evidence/cycle28/CYCLE.archived.json").read_bytes()
        self.assertEqual("67596f292a72ae89685da8d20e8687a39a31fd3c3aa4097706eba475adbcf31d",
                         hashlib.sha256(archived).hexdigest())
        self.assertIn("NOT_RUN", json.dumps(state))

    def test_release_patch_applies_only_in_fixture_and_never_changes_qa_or_signing(self):
        source = (ROOT / "app/build.gradle.kts").read_bytes()
        delta = ROOT / "workflow/evidence/cycle29/release-enable-after-live.patch"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app").mkdir()
            (root / "app/build.gradle.kts").write_bytes(source)
            for args in (("--check",), ()):
                subprocess.run(["git", "apply", *args, str(delta)], cwd=root, check=True, capture_output=True)
            changed = (root / "app/build.gradle.kts").read_text()
            original = source.decode()
            prefix, suffix = original.split("        release {\n", 1)
            old_release, old_rest = suffix.split("\n        }", 1)
            new_prefix, new_suffix = changed.split("        release {\n", 1)
            new_release, new_rest = new_suffix.split("\n        }", 1)
            self.assertEqual(prefix, new_prefix)
            self.assertEqual(old_rest, new_rest)
            for flag in ("YANDEX_ID", "FAMILY_LINK_UI", "FAMILY_ROSTER_V2", "ALICE"):
                self.assertIn('buildConfigField("boolean", "' + flag + '", "true")', new_release)
                self.assertNotIn('buildConfigField("boolean", "' + flag + '", "true")', old_release)
            self.assertIn('buildConfigField("boolean", "YANDEX_ID", "false")', old_release)
            self.assertEqual(1, changed.count('signingConfigs.findByName("release")'))
        self.assertEqual(source, (ROOT / "app/build.gradle.kts").read_bytes())

    def test_owner_2115_all_four_qa_flags_on_release_defaults_off_debug_voice_kept(self):
        source = (ROOT / "app/build.gradle.kts").read_text()
        defaults = source.split("    defaultConfig {\n", 1)[1].split("\n    signingConfigs {", 1)[0]
        release = source.split("        release {\n", 1)[1].split("\n        }", 1)[0]
        debug = source.split("        debug {\n", 1)[1].split("\n        }", 1)[0]
        qa = source.split('    buildTypes.create("qa") {\n', 1)[1].split("\n    }", 1)[0]

        def fields(block):
            return dict(re.findall(r'buildConfigField\("boolean", "([A-Z_0-9]+)", (.+)\)', block))

        default_flags, release_flags = fields(defaults), fields(release)
        qa_flags, debug_flags = fields(qa), fields(debug)
        self.assertIn('initWith(buildTypes.getByName("release"))', qa)
        approval = re.search(r"val qaYandexActivationApproved = (\w+)", source)
        assert approval is not None
        self.assertEqual("true", approval.group(1))
        for flag in ("YANDEX_ID", "FAMILY_LINK_UI", "FAMILY_ROSTER_V2", "ALICE"):
            with self.subTest(flag=flag):
                self.assertEqual('"false"', release_flags.get(flag, default_flags.get(flag)))
                self.assertEqual('qaYandexActivationApproved.toString()' if flag == "YANDEX_ID" else '"true"', qa_flags[flag])
        self.assertEqual('"false"', default_flags["ALICE"])
        self.assertEqual('"true"', debug_flags["ALICE"])
        for flag in ("FAMILY_LINK_UI", "FAMILY_ROSTER_V2"):
            self.assertEqual('"false"', debug_flags.get(flag, default_flags.get(flag)))


if __name__ == "__main__":
    unittest.main()
