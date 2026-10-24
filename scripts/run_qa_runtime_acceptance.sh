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
adb pull /sdcard/Android/data/com.polinalinen.madre.macrobenchmark/files/huawei-p0 "$out/native" || true
adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n com.polinalinen.madre.qa/androidx.profileinstaller.ProfileInstallReceiver > "$out/profile-install.txt"
adb shell cmd package compile -f -m speed-profile com.polinalinen.madre.qa > "$out/profile-compile.txt"
adb shell dumpsys package com.polinalinen.madre.qa > "$out/actual-installed-package.txt"
adb logcat -d > "$out/runtime-logcat.txt"
python3 - "$out" <<'PY'
import pathlib,re,sys
out=pathlib.Path(sys.argv[1])
assert re.search(r'result=[12]\b', (out/'profile-install.txt').read_text()),'Actual profile installer did not report success/already installed'
assert 'Success' in (out/'profile-compile.txt').read_text(),'Actual speed-profile compilation failed'
assert 'speed-profile' in (out/'actual-installed-package.txt').read_text(),'Installed package does not report actual speed-profile compilation'
import hashlib,json
(out/'profile-runtime.json').write_text(json.dumps({'apk_sha256':hashlib.sha256((out/'app-qa.apk').read_bytes()).hexdigest(),'ProfileInstaller_success_or_already_installed':True,'actual_dexopt_status':'speed-profile','physical_Huawei_acceptance':False},indent=2)+'\n')
PY
exit "$result"
