"""Executable orchestration tests use explicit synthetic bytes/adb responses, never performance claims."""
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from scripts import run_exact_qa_timing as timing

ROOT = Path(__file__).resolve().parents[2]


class ExactQaTimingTests(unittest.TestCase):
    def test_workflow_uses_exact_artifacts_and_builds_only_driver_before_emulator(self):
        workflow = (ROOT / '.github/workflows/quality-gates.yml').read_text()
        step = workflow.split('name: Prepare only external timing driver', 1)[1].split('      - name:', 1)[0]
        self.assertIn(':macrobenchmark:assembleBenchmark', step)
        self.assertNotIn(':app:', step)
        self.assertIn('continue-on-error: true', step)  # ordinary lanes still run; exact runtime then fails closed
        self.assertLess(workflow.index('name: Prepare only external timing driver'), workflow.index('name: Run emulator smoke tests'))
        before = workflow.split('name: Download original issued15 bytes', 1)[1].split('      - name:', 1)[0]
        self.assertIn('run-id: 40096655873', before)
        self.assertIn('name: madre-debug-apk', before)
        upload = workflow.split('name: Upload exact frozen QA timing', 1)[1].split('      - name:', 1)[0]
        self.assertIn('if: always() && inputs.exact_qa_timing', upload)

    def exercise_runtime(self, failure=False, installed=False, driver=timing.DRIVER):
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as temporary:
            out = Path(temporary)
            for name in ['driver', 'fixture']:
                (out / (name + '.apk')).write_bytes(b'explicit synthetic orchestration fixture ' + name.encode())
            controls = {'head': 'test-head', 'driver_package': driver, 'driver_sha256': timing.sha(out / 'driver.apk'),
                        'fixture_sha256': timing.sha(out / 'fixture.apk'), 'payloads': {}}
            for arm in ['before15', 'after16']:
                dest = out / arm
                dest.mkdir()
                (dest / 'target.apk').write_bytes(b'explicit synthetic target ' + arm.encode())
                controls['payloads'][arm] = {'apk_sha256': timing.sha(dest / 'target.apk'),
                    'suppressErrors': 'EMULATOR,DEBUGGABLE' if arm == 'before15' else 'EMULATOR'}
            (out / 'controls.json').write_text(json.dumps(controls))
            calls = []
            current = None
            def fake_run(args, **kwargs):
                nonlocal current
                args = list(map(str, args))
                calls.append(args)
                text = ''
                if args == ['git', 'rev-parse', 'HEAD']:
                    text = 'test-head\n'
                elif args[:4] == ['adb', 'shell', 'pm', 'list']:
                    if args[4] == 'packages':
                        text = 'package:' + timing.PACKAGE + '\n' if installed else ''
                    else:
                        text = f'instrumentation:{driver}/runner (target={driver})\n'
                elif args[:3] == ['adb', 'install', '-r'] and args[-1].endswith('target.apk'):
                    current = Path(args[-1])
                elif args[:4] == ['adb', 'shell', 'pm', 'path']:
                    text = 'package:/data/app/example/base.apk\n'
                elif args[:3] == ['adb', 'shell', 'sha256sum']:
                    text = timing.sha(current) + ' /data/app/example/base.apk\n'
                elif args[:5] == ['adb', 'shell', 'am', 'instrument', '-w']:
                    arm = args[args.index('madre.exactQaArm') + 1]
                    text = 'FAILURES!!!' if failure and arm == 'before15' else 'OK (3 tests)'
                if kwargs.get('log'):
                    kwargs['log'].write_text(text)
                return subprocess.CompletedProcess(args, 0, text)
            phases = []
            with patch.object(timing, 'OUT', out), patch.object(timing, 'run', side_effect=fake_run), \
                 patch.object(timing, 'guard_serial') as guard, \
                 patch.object(timing, 'fixture_phase', side_effect=lambda phase, dest: phases.append(phase)), \
                 patch.object(timing, 'collect_raw') as collect, patch.object(timing, 'validate_raw') as validate, \
                 patch.dict(os.environ, {'ANDROID_SERIAL': 'emulator-5554'}):
                if failure or installed:
                    with self.assertRaises(AssertionError):
                        timing.runtime()
                else:
                    timing.runtime()
                return calls, phases, collect.call_count, validate.call_count, guard.call_count

    def test_exact_runtime_installs_15_then_16_without_clear_reseed_rebuild_or_downgrade(self):
        calls, phases, collected, validated, guards = self.exercise_runtime()
        targets = [c[-1] for c in calls if c[:3] == ['adb', 'install', '-r'] and c[-1].endswith('target.apk')]
        self.assertTrue(targets[0].endswith('/before15/target.apk'))
        self.assertTrue(targets[1].endswith('/after16/target.apk'))
        self.assertEqual(['exact-timing-seed-15', 'exact-timing-check-15', 'exact-timing-check-16', 'exact-timing-check-16'], phases)
        commands = [c for c in calls if 'madre.exactQaArm' in c]
        self.assertEqual(['EMULATOR,DEBUGGABLE', 'EMULATOR'], [c[c.index('androidx.benchmark.suppressErrors') + 1] for c in commands])
        for c in calls:
            if c[:2] == ['adb', 'install']:
                self.assertNotIn('-d', c)
            self.assertNotIn('uninstall', c)
            self.assertNotIn('clear', c)
            self.assertNotIn('gradlew', ' '.join(c))
        self.assertEqual((2, 2, 4), (collected, validated, guards))

    def test_failed_before_arm_keeps_raw_and_attempts_real_after_without_fake_pass(self):
        calls, phases, collected, validated, guards = self.exercise_runtime(failure=True)
        self.assertEqual(2, collected)
        self.assertEqual(1, validated)
        self.assertEqual(2, len([c for c in calls if 'madre.exactQaArm' in c]))

    def test_driver_package_is_discovered_not_guessed_or_instrumented_into_target(self):
        driver = timing.DRIVER + '.test'
        calls, phases, collected, validated, guards = self.exercise_runtime(driver=driver)
        commands = [c for c in calls if 'madre.exactQaArm' in c]
        self.assertEqual([driver + '/runner'] * 2, [c[-1] for c in commands])
        self.assertTrue(any('/sdcard/Android/data/' + driver + '/files/exact-qa-routes' in c for c in calls))

    def test_preinstalled_qa_is_a_hard_error_not_an_uninstall_workaround(self):
        calls, phases, collected, validated, guards = self.exercise_runtime(installed=True)
        self.assertFalse(any(c[:2] == ['adb', 'install'] for c in calls))
        self.assertEqual([], phases)

    def synthetic_raw(self, out):
        raw = out / 'raw'
        raw.mkdir()
        benches = []
        for name in ['mainActivityFrames', 'ovenScroll', 'tocScroll']:
            traces = []
            for i in range(5):
                filename = name + str(i) + '.perfetto-trace'
                (raw / filename).write_bytes(b'synthetic validator test only')
                traces.append({'type': 'PerfettoTrace', 'filename': filename})
            benches.append({'name': name, 'className': timing.CLASS, 'repeatIterations': 5,
                'metrics': {'frameCount': {'runs': [1] * 5}},
                'sampledMetrics': {k: {'runs': [[1]] * 5} for k in ['frameDurationCpuMs', 'frameOverrunMs']},
                'profilerOutputs': traces})
        return {'benchmarks': benches}

    def test_raw_validator_rejects_missing_samples_iterations_traces_and_wrong_activity_suite(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as temporary:
            out = Path(temporary)
            data = self.synthetic_raw(out)
            target = out / 'raw/test-benchmarkData.json'
            target.write_text(json.dumps(data))
            timing.validate_raw(out)
            for field in ['iterations', 'samples', 'class', 'frameCount']:
                broken = copy.deepcopy(data)
                if field == 'iterations': broken['benchmarks'][0]['repeatIterations'] = 4
                if field == 'samples': broken['benchmarks'][0]['sampledMetrics']['frameDurationCpuMs']['runs'][0] = []
                if field == 'class': broken['benchmarks'][0]['className'] = 'ScrollBenchmarks'
                if field == 'frameCount': broken['benchmarks'][0]['metrics']['frameCount']['runs'][0] = 0
                target.write_text(json.dumps(broken))
                with self.subTest(field=field), self.assertRaises(AssertionError): timing.validate_raw(out)
            target.write_text(json.dumps(data))
            next((out / 'raw').glob('*.perfetto-trace')).unlink()
            with self.assertRaises(AssertionError): timing.validate_raw(out)

    def test_target_driver_keeps_true_main_activity_and_strict_after_guards(self):
        source = (ROOT / 'macrobenchmark/src/main/java/com/polinalinen/madre/macrobenchmark/ExactQaTiming.kt').read_text()
        self.assertIn('com.polinalinen.madre.MainActivity', source)
        self.assertNotIn('RazvorotSceneActivity', source)
        self.assertIn('iterations = 5', source)
        self.assertIn('FrameTimingMetric()', source)
        self.assertIn('CompilationMode.None()', source)
        self.assertIn('isProfileableByShell', source)
        self.assertIn('device.currentPackageName == target', source)
        fixture = (ROOT / 'app/src/qaRegression/java/com/polinalinen/madre/MinifiedPlanInstrumentation.java').read_text()
        self.assertIn('else if(debuggable) throw new AssertionError("Delivery target must be non-debuggable")', fixture)
        self.assertIn('version!=(before?51L:52L) || debuggable!=before', fixture)
        self.assertIn('if(!timing)', fixture)  # original strict 3-plan/unique-number delivery assertions preserved


if __name__ == '__main__':
    unittest.main()
