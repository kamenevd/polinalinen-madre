"""Exercise the real coordinator using explicit fake lanes, never adb/Gradle."""
import itertools
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class NativeAcceptanceTests(unittest.TestCase):
    def run_coordinator(self, codes, native="true", missing=None, broken_log=False, serial="emulator-5554", exact="false"):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as temporary:
            root = Path(temporary)
            scripts = root / "scripts"
            scripts.mkdir()
            shutil.copy2(ROOT / "scripts/run_native_acceptance.sh", scripts)
            tools = root / "bin"
            tools.mkdir()
            adb = tools / "adb"
            adb.write_text(f"#!/bin/sh\ntest \"$1\" = get-serialno || exit 99\nprintf '{serial}\\n'\n")
            adb.chmod(0o755)
            lanes = [
                ("qa-runtime", "run_qa_runtime_acceptance.sh", "sh"),
                ("minified-persistence", "run_minified_plan_regression.py", "py"),
                ("legacy-debug-domain", "run_instrumented_acceptance.sh", "sh"),
            ]
            if exact == "true":
                lanes.insert(0, ("exact-qa-timing", "run_exact_qa_timing.py", "py"))
            for (name, filename, kind), code in zip(lanes, codes):
                if filename == missing:
                    continue
                if kind == "py":
                    source = (f"from pathlib import Path\nimport os\nassert os.environ['ANDROID_SERIAL'] == 'emulator-5554'\nprint('{name} output', flush=True)\n"
                              f"with Path('order.txt').open('a') as output: output.write('{name}\\n')\n"
                              f"raise SystemExit({code})\n")
                else:
                    source = f"test \"$ANDROID_SERIAL\" = emulator-5554 || exit 99\nprintf '{name}\\n' >> order.txt\nprintf '{name} output\\n'\nexit {code}\n"
                (scripts / filename).write_text(source)
            if broken_log:
                (root / "performance-evidence/native-acceptance/qa-runtime.log").mkdir(parents=True)
            result = subprocess.run(["bash", "scripts/run_native_acceptance.sh", native, exact],
                                    cwd=root, env={**os.environ, "PATH": str(tools) + os.pathsep + os.environ["PATH"]},
                                    capture_output=True, text=True)
            out = root / "performance-evidence/native-acceptance"
            reports = {p.name: p.read_text() for p in out.glob("*") if p.is_file()}
            order = (root / "order.txt").read_text().splitlines() if (root / "order.txt").exists() else []
            return result, order, reports

    def test_all_failure_combinations_run_every_group_and_preserve_exact_codes(self):
        for codes in itertools.product((0, 7), (0, 23), (0, 41)):
            with self.subTest(codes=codes):
                result, order, reports = self.run_coordinator(codes)
                self.assertEqual(["qa-runtime", "minified-persistence", "legacy-debug-domain"], order)
                self.assertEqual(int(any(codes)), result.returncode, result.stderr)
                expected = "group\texit_code\tlog_exit_code\n" + "".join(
                    f"{name}\t{code}\t0\n" for name, code in zip(order, codes))
                self.assertEqual(expected, reports["exit-codes.tsv"])
                self.assertEqual(f"{result.returncode}\n", reports["overall-exit-code.txt"])
                for name in order:
                    self.assertEqual(f"{name} output\n", reports[f"{name}.log"])
                    self.assertIn(f"{name} output", result.stdout)

    def test_non_native_keeps_legacy_domain_lane_and_failure(self):
        result, order, reports = self.run_coordinator((7, 23, 41), native="false")
        self.assertEqual(["legacy-debug-domain"], order)
        self.assertEqual(1, result.returncode)
        self.assertEqual("group\texit_code\tlog_exit_code\nlegacy-debug-domain\t41\t0\n",
                         reports["exit-codes.tsv"])

    def test_exact_first_all_sixteen_failure_combinations_keep_every_original_code(self):
        for codes in itertools.product((0, 13), (0, 7), (0, 23), (0, 41)):
            with self.subTest(codes=codes):
                result, order, reports = self.run_coordinator(codes, exact="true")
                self.assertEqual(["exact-qa-timing", "qa-runtime", "minified-persistence", "legacy-debug-domain"], order)
                self.assertEqual(int(any(codes)), result.returncode)
                self.assertEqual("group\texit_code\tlog_exit_code\n" + "".join(
                    f"{name}\t{code}\t0\n" for name, code in zip(order, codes)), reports["exit-codes.tsv"])

    def test_exact_alone_also_keeps_ordinary_minified_legacy(self):
        result, order, reports = self.run_coordinator((13, 0, 0, 0), exact="true", native="false")
        self.assertEqual(1, result.returncode)
        self.assertEqual(["exact-qa-timing", "qa-runtime", "minified-persistence", "legacy-debug-domain"], order)

    def test_exact_preparation_missing_still_runs_every_ordinary_lane(self):
        result, order, reports = self.run_coordinator((0, 0, 0, 0), exact="true", missing="run_exact_qa_timing.py")
        self.assertEqual(1, result.returncode)
        self.assertEqual(["qa-runtime", "minified-persistence", "legacy-debug-domain"], order)
        self.assertIn("exact-qa-timing\t2\t0\n", reports["exit-codes.tsv"])

    def test_exact_log_failure_cannot_hide_later_group_results(self):
        result, order, reports = self.run_coordinator((0, 7, 23, 41), exact="true", broken_log=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("exact-qa-timing\t0\t0\n", reports["exit-codes.tsv"])
        self.assertIn("qa-runtime\t7\t1\n", reports["exit-codes.tsv"])
        self.assertIn("minified-persistence\t23\t0\n", reports["exit-codes.tsv"])
        self.assertIn("legacy-debug-domain\t41\t0\n", reports["exit-codes.tsv"])

    def test_invalid_mode_fails_before_any_lane(self):
        result, order, reports = self.run_coordinator((0, 0, 0), native="invalid")
        self.assertEqual(2, result.returncode)
        self.assertEqual([], order)
        self.assertEqual({}, reports)

    def test_non_emulator_serial_is_rejected_before_all_lanes(self):
        result, order, reports = self.run_coordinator((0, 0, 0), serial="family-phone")
        self.assertEqual(2, result.returncode)
        self.assertEqual([], order)
        self.assertEqual({}, reports)
        self.assertIn("Only the authorized CI emulator is admitted", result.stderr)

    def test_missing_qa_script_still_runs_minified_and_legacy_and_fails(self):
        result, order, reports = self.run_coordinator((0, 0, 0), missing="run_qa_runtime_acceptance.sh")
        self.assertEqual(1, result.returncode)
        self.assertEqual(["minified-persistence", "legacy-debug-domain"], order)
        self.assertIn("qa-runtime\t127\t0\n", reports["exit-codes.tsv"])
        self.assertIn("legacy-debug-domain\t0\t0\n", reports["exit-codes.tsv"])

    def test_log_failure_is_not_success_and_does_not_stop_later_groups(self):
        result, order, reports = self.run_coordinator((0, 0, 0), broken_log=True)
        self.assertEqual(1, result.returncode)
        self.assertEqual(["qa-runtime", "minified-persistence", "legacy-debug-domain"], order)
        self.assertIn("qa-runtime\t0\t1\n", reports["exit-codes.tsv"])
        self.assertIn("legacy-debug-domain\t0\t0\n", reports["exit-codes.tsv"])


if __name__ == "__main__":
    unittest.main()
