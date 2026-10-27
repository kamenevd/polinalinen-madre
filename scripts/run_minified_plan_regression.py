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
import sys
import tarfile
import time

ROOT = Path(__file__).resolve().parents[1]
BASE = '2880abbe51d85145c4b666d60f35153e05a02727'
PACKAGE = 'com.polinalinen.madre.qa'
PIN = '9ACA5F5F57F301EC8359AD670ECEB6387156132007C07FF36A31266326468258'
OUT = ROOT / 'performance-evidence/qa-runtime/plan-regression'
OUT.mkdir(parents=True, exist_ok=True)


transport_events=[]


def transport_event(event):
    transport_events.append(event)
    (OUT/'adb-transport.json').write_text(json.dumps(transport_events,indent=2)+'\n')


def wait_for_emulator():
    # A temporary offline transport is infrastructure, not an assertion PASS.
    # Keep the selected authorized serial; never restart the adb server/devices.
    for attempt in range(3):
        try:
            available=subprocess.run(['adb','wait-for-device'],capture_output=True,text=True,timeout=120)
            boot=subprocess.run(['adb','shell','getprop ro.kernel.qemu; getprop sys.boot_completed'],capture_output=True,text=True,timeout=20)
            transport_event({'stage':'ready','attempt':attempt,'wait_code':available.returncode,'boot_code':boot.returncode,'stdout':boot.stdout,'stderr':available.stderr+boot.stderr})
            if available.returncode==boot.returncode==0 and boot.stdout.split()==['1','1']:
                return
        except subprocess.TimeoutExpired:
            transport_event({'stage':'ready','attempt':attempt,'timeout':True})
        time.sleep(2)
    raise RuntimeError('Selected CI emulator did not regain a booted adb transport; no data test PASS')


def run(command, **kwargs):
    if command[0]!='adb':
        return subprocess.run(command, check=True, **kwargs)
    # Only idempotent install/force-stop/pull transport operations are retried.
    # Actual instrumentation/assertions below are never retried or suppressed.
    assert (command[1:3] == ['install', '-r'] or command[1:2] == ['pull']
            or command[1:5] == ['shell', 'am', 'force-stop', PACKAGE]), 'Not a retryable adb operation'
    for attempt in range(3):
        wait_for_emulator()
        result=subprocess.run(command,capture_output=True,text=True,timeout=120,**kwargs)
        transport_event({'stage':'command','command':command,'attempt':attempt,'returncode':result.returncode,'stdout':result.stdout,'stderr':result.stderr})
        print(result.stdout,end='',flush=True)
        print(result.stderr,end='',file=sys.stderr,flush=True)
        if result.returncode==0:return result
        if not any(message in (result.stdout+result.stderr).lower() for message in ['device offline','device not found','no devices/emulators found','device still connecting']):
            result.check_returncode()
    result.check_returncode()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


before = Path(os.environ['RUNNER_TEMP']) / 'madre-minified-plans-before'


def source_hashes():
    return {str(path.relative_to(before)): sha(path) for path in (before / 'app/src/main').rglob('*') if path.is_file()}


negative_apk = before / 'app/build/outputs/apk/qa/app-qa.apk'
negative_test = before / 'app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk'
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
assert sys.argv[1:] in ([],['--prepare'])
prepared=before/'minified-prepared.json'
if sys.argv[1:]==['--prepare']:
    assert not before.exists(), 'Never reset another checkout or existing data'
    before.mkdir()
    with tarfile.open(fileobj=io.BytesIO(subprocess.check_output(['git','archive',BASE],cwd=ROOT))) as archive:
        archive.extractall(before,filter='data')
    original=source_hashes()
    original_rules=sha(before/'app/proguard-rules.pro')
    for name in ['build.gradle.kts','qa-test-rules.pro','qa-persistence-api.pro']:
        shutil.copy2(ROOT/'app'/name,before/'app'/name)
    shutil.copytree(ROOT/'app/src/qaRegression',before/'app/src/qaRegression')
    assert source_hashes()==original and sha(before/'app/proguard-rules.pro')==original_rules
    # Heavy Gradle/R8 finishes BEFORE AVD boot, avoiding pressure on its adbd.
    run([str(before/'gradlew'),':app:assembleQa',':app:assembleQaAndroidTest','-Pmadre.qaRegression=true','--no-daemon','--stacktrace','--no-configuration-cache'],cwd=before)
    prepared.write_text(json.dumps({'controller_head':head,'main':original,'rules':original_rules,'apk':sha(negative_apk),'test_apk':sha(negative_test)},indent=2)+'\n')
    print('Actual immutable negative R8 control prepared before AVD boot',flush=True)
    raise SystemExit(0)

metadata=json.loads(prepared.read_text())
assert metadata['controller_head']==head,'Prepared control belongs to another source head'
original,original_rules=metadata['main'],metadata['rules']
assert source_hashes()==original and sha(before/'app/proguard-rules.pro')==original_rules
assert sha(negative_apk)==metadata['apk'] and sha(negative_test)==metadata['test_apk']
shutil.copy2(prepared,OUT/'prepared-control.json')
serial = subprocess.check_output(['adb', 'get-serialno'], text=True).strip()
assert serial.startswith('emulator-'), 'Only the authorized CI emulator is admitted'
os.environ['ANDROID_SERIAL'] = serial
wait_for_emulator()
exact = Path(os.environ['MADRE_EXACT_QA_REGRESSION_DIR'])
positive_apks = list(exact.rglob('app-qa.apk'))
positive_mappings = list(exact.rglob('mapping.txt'))
positive_tests = list(Path(os.environ['MADRE_EXACT_QA_TEST_DIR']).rglob('*.apk'))
assert len(positive_apks) == len(positive_mappings) == len(positive_tests) == 1
positive_apk, positive_mapping, positive_test = positive_apks[0], positive_mappings[0], positive_tests[0]

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
        'build_overlay': ['app/build.gradle.kts', 'app/qa-test-rules.pro', 'app/qa-persistence-api.pro', 'app/src/qaRegression/'] if label == 'negative-before' else [],
        'main_source_sha256': original if label == 'negative-before' else {},
        'original_proguard_sha256': original_rules if label == 'negative-before' else sha(ROOT / 'app/proguard-rules.pro'),
        'target_apk_sha256': sha(apk), 'instrumentation_apk_sha256': sha(test),
        'exact_quality_artifact': label == 'positive-after', 'family_device': False,
        'test_only_persistence_ABI': True, 'delivery_candidate': False,
    }, indent=2) + '\n')
    # No production app, login or global settings. Test data lives in a synthetic namespace.
    run(['adb', 'install', '-r', str(apk)])
    run(['adb', 'install', '-r', str(test)])
    for phase in ('write', 'restart'):
        run(['adb', 'shell', 'am', 'force-stop', PACKAGE])
        wait_for_emulator()
        result = subprocess.run(['adb', 'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', 'com.polinalinen.madre.MinifiedPlanRegressionTest',
            '-e', 'negative', 'true' if label == 'negative-before' else 'false', '-e', 'phase', phase,
            PACKAGE + '.test/com.polinalinen.madre.MinifiedPlanInstrumentation'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,timeout=120)
        (destination / f'{phase}-instrumentation.txt').write_text(result.stdout)
        logcat=subprocess.run(['adb','logcat','-d','-t','2000'],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        (destination / f'{phase}-logcat.txt').write_text(logcat.stdout)
        assert result.returncode == 0 and 'INSTRUMENTATION_RESULT: minifiedPersistence=PASS' in result.stdout and 'INSTRUMENTATION_CODE: -1' in result.stdout and 'minifiedPersistence=FAIL' not in result.stdout, f'{label}/{phase} actual instrumentation failed; inspect raw output'
    run(['adb', 'pull', f'/sdcard/Android/data/{PACKAGE}/files/minified-plan-regression', str(destination / 'raw')])
    assert all((destination / 'raw' / f'{phase}-prefs.json').is_file() for phase in ('write', 'restart'))
    pids=[(destination/'raw'/f'{phase}-result.txt').read_text().split('pid=')[1].split(';')[0] for phase in ('write','restart')]
    assert pids[0]!=pids[1],f'{label}: write and restart must be distinct actual app processes'
    if label=='positive-after':
        errors=list((destination/'raw').glob('error-*-unchanged.json'))
        assert len(errors)==4
        for error in errors:
            proof=json.loads(error.read_text())
            assert proof['before']==proof['after'] and proof['all_original_prefs_and_capabilities_unchanged']
    print(label, 'actual minified write/restart PASS', sha(apk),flush=True)

(OUT / 'result.json').write_text(json.dumps({'negative_before': 'reproduced_hidden_and_overwritten_legacy_plans',
    'positive_after': 'legacy_and_new_plans_survive_new_process', 'failed_read': 'prefs_and_capabilities_unchanged',
    'test_only_quality_apk_sha256': sha(positive_apk), 'delivery_candidate': False,
    'physical_Huawei_acceptance': False}, indent=2) + '\n')
