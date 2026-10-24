#!/usr/bin/env bash
# Authorized CI emulator only; never a family phone.
set -euo pipefail
out="$PWD/performance-evidence/qa-runtime"
mkdir -p "$out"
./gradlew :app:assembleQa --no-daemon --stacktrace --no-configuration-cache
python3 scripts/verify_apk_signature.py app/build/outputs/apk/qa/app-qa.apk --expected 9ACA5F5F57F301EC8359AD670ECEB6387156132007C07FF36A31266326468258
python3 scripts/verify_qa_release_like.py app/build/outputs/apk/qa/app-qa.apk --mapping app/build/outputs/mapping/qa/mapping.txt > "$out/qa-binary-provenance.json"
cp app/build/outputs/apk/qa/app-qa.apk "$out/"
cp app/build/outputs/mapping/qa/mapping.txt "$out/"
adb install -r "$out/app-qa.apk"
adb shell pm grant com.polinalinen.madre.qa android.permission.POST_NOTIFICATIONS
result=0
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.polinalinen.madre.macrobenchmark.QaOwnerAcceptance --no-daemon --stacktrace --no-configuration-cache || result=$?
adb pull /sdcard/Download/madre-qa-p0 "$out/native"
cp -R macrobenchmark/build/outputs/androidTest-results "$out/qa-test-results"
adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n com.polinalinen.madre.qa/androidx.profileinstaller.ProfileInstallReceiver > "$out/profile-install.txt"
adb shell cmd package compile -f -m speed-profile com.polinalinen.madre.qa > "$out/profile-compile.txt"
adb shell dumpsys package com.polinalinen.madre.qa > "$out/actual-installed-package.txt"
adb logcat -d > "$out/runtime-logcat.txt"
python3 - "$out" <<'PY'
import pathlib,re,sys,xml.etree.ElementTree as ET
out=pathlib.Path(sys.argv[1])
expected=['qa-haptic-diagnostics','qa-fps-settings','qa-night-settings','qa-night-toc']
for name in expected:
    assert (out/'native'/f'{name}.png').is_file(),f'Missing full raw frame: {name}'
    tree=ET.parse(out/'native'/f'{name}.xml')
    assert any(n.attrib.get('package')=='com.polinalinen.madre.qa' for n in tree.iter('node')),'Actual QA window absent from frame hierarchy'
cases=[case for p in (out/'qa-test-results').rglob('TEST-*.xml') for case in ET.parse(p).getroot().iter('testcase') if case.attrib.get('classname','').endswith('QaOwnerAcceptance')]
assert len(cases)==1 and all(case.find(tag) is None for case in cases for tag in ['failure','error','skipped']),'Actual QA UI test did not pass exactly once'
assert re.search(r'result=[12]\b', (out/'profile-install.txt').read_text()),'Actual profile installer did not report success/already installed'
assert 'Success' in (out/'profile-compile.txt').read_text(),'Actual speed-profile compilation failed'
assert 'speed-profile' in (out/'actual-installed-package.txt').read_text(),'Installed package does not report actual speed-profile compilation'
import hashlib,json
(out/'profile-runtime.json').write_text(json.dumps({'apk_sha256':hashlib.sha256((out/'app-qa.apk').read_bytes()).hexdigest(),'ProfileInstaller_success_or_already_installed':True,'actual_dexopt_status':'speed-profile','physical_Huawei_acceptance':False},indent=2)+'\n')
PY
exit "$result"
