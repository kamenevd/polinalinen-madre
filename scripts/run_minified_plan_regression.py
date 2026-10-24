#!/usr/bin/env python3
"""Authorized CI AVD: actual minified before/after plan persistence, two app processes.
The negative control archives 2880abb, overlays only build/test harness, never main/proguard.
The positive target/test APKs are downloaded unchanged from this run's quality artifact.
"""
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
BASE = '2880abbe51d85145c4b666d60f35153e05a02727'
PACKAGE = 'com.polinalinen.madre.qa'
PIN = '9ACA5F5F57F301EC8359AD670ECEB6387156132007C07FF36A31266326468258'
OUT = ROOT / 'performance-evidence/qa-runtime/plan-regression'
OUT.mkdir(parents=True, exist_ok=True)


def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


serial = subprocess.check_output(['adb', 'get-serialno'], text=True).strip()
assert serial.startswith('emulator-'), 'Only the authorized CI emulator is admitted'
os.environ['ANDROID_SERIAL'] = serial
assert subprocess.check_output(['adb', 'shell', 'getprop', 'ro.kernel.qemu'], text=True).strip() == '1'
before = Path(os.environ['RUNNER_TEMP']) / 'madre-minified-plans-before'
assert not before.exists(), 'Never reset another checkout or existing data'
before.mkdir()
with tarfile.open(fileobj=io.BytesIO(subprocess.check_output(['git', 'archive', BASE], cwd=ROOT))) as archive:
    archive.extractall(before, filter='data')


def source_hashes():
    return {str(path.relative_to(before)): sha(path) for path in (before / 'app/src/main').rglob('*') if path.is_file()}


original = source_hashes()
original_rules = sha(before / 'app/proguard-rules.pro')
shutil.copy2(ROOT / 'app/build.gradle.kts', before / 'app/build.gradle.kts')
shutil.copy2(ROOT / 'app/qa-test-rules.pro', before / 'app/qa-test-rules.pro')
shutil.copytree(ROOT / 'app/src/qaRegression', before / 'app/src/qaRegression')
assert source_hashes() == original and sha(before / 'app/proguard-rules.pro') == original_rules
run([str(before / 'gradlew'), ':app:assembleQa', ':app:assembleQaAndroidTest', '-Pmadre.qaRegression=true', '--no-daemon', '--stacktrace', '--no-configuration-cache'], cwd=before)
negative_apk = before / 'app/build/outputs/apk/qa/app-qa.apk'
negative_test = before / 'app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk'
exact = Path(os.environ['MADRE_EXACT_QA_DIR'])
positive_apks = list(exact.rglob('app-qa.apk'))
positive_mappings = list(exact.rglob('mapping.txt'))
positive_tests = list(Path(os.environ['MADRE_EXACT_QA_TEST_DIR']).rglob('*.apk'))
assert len(positive_apks) == len(positive_mappings) == len(positive_tests) == 1
positive_apk, positive_mapping, positive_test = positive_apks[0], positive_mappings[0], positive_tests[0]
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()

for label, apk, test, mapping, source in [
    ('negative-before', negative_apk, negative_test, before / 'app/build/outputs/mapping/qa/mapping.txt', BASE),
    ('positive-after', positive_apk, positive_test, positive_mapping, head),
]:
    destination = OUT / label
    destination.mkdir()
    for item in (apk, test):
        run(['python3', str(ROOT / 'scripts/verify_apk_signature.py'), str(item), '--expected', PIN])
        shutil.copy2(item, destination / item.name)
    shutil.copy2(mapping, destination / 'mapping.txt')
    proof = subprocess.check_output(['python3', str(ROOT / 'scripts/verify_qa_release_like.py'), str(apk), '--mapping', str(mapping)], cwd=ROOT, text=True)
    (destination / 'binary-verification.json').write_text(proof)
    (destination / 'source-build.json').write_text(json.dumps({
        'functional_source': source, 'build_controller_head': head,
        'build_overlay': ['app/build.gradle.kts', 'app/qa-test-rules.pro', 'app/src/qaRegression/'] if label == 'negative-before' else [],
        'main_source_sha256': original if label == 'negative-before' else {},
        'original_proguard_sha256': original_rules if label == 'negative-before' else sha(ROOT / 'app/proguard-rules.pro'),
        'target_apk_sha256': sha(apk), 'instrumentation_apk_sha256': sha(test),
        'exact_quality_artifact': label == 'positive-after', 'family_device': False,
    }, indent=2) + '\n')
    # No production app, login or global settings. Test data lives in a synthetic namespace.
    run(['adb', 'install', '-r', str(apk)])
    run(['adb', 'install', '-r', str(test)])
    for phase in ('write', 'restart'):
        run(['adb', 'shell', 'am', 'force-stop', PACKAGE])
        result = subprocess.run(['adb', 'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', 'com.polinalinen.madre.MinifiedPlanRegressionTest',
            '-e', 'negative', 'true' if label == 'negative-before' else 'false', '-e', 'phase', phase,
            PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (destination / f'{phase}-instrumentation.txt').write_text(result.stdout)
        assert result.returncode == 0 and 'OK (1 test)' in result.stdout and 'FAILURES' not in result.stdout, f'{label}/{phase} actual instrumentation failed; inspect raw output'
    run(['adb', 'pull', f'/sdcard/Android/data/{PACKAGE}/files/minified-plan-regression', str(destination / 'raw')])
    assert all((destination / 'raw' / f'{phase}-prefs.json').is_file() for phase in ('write', 'restart'))
    print(label, 'actual minified write/restart PASS', sha(apk))

(OUT / 'result.json').write_text(json.dumps({'negative_before': 'reproduced_hidden_and_overwritten_legacy_plans',
    'positive_after': 'legacy_and_new_plans_survive_new_process', 'failed_read': 'prefs_and_capabilities_unchanged',
    'exact_quality_apk_sha256': sha(positive_apk), 'physical_Huawei_acceptance': False}, indent=2) + '\n')
