#!/usr/bin/env bash
# CI emulator only. Keep this a single action command: the pinned emulator runner
# splits its `script` input into independent `sh -c` lines (no multiline if/trap scope).
set -euo pipefail
mkdir -p screenshots/cutout
adb shell wm size 412x915
adb shell wm density 160
adb shell wm size
adb shell wm density
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell cmd overlay enable com.android.internal.display.cutout.emulation.tall
adb shell cmd overlay list > screenshots/cutout/overlay-list.txt
adb shell dumpsys display > screenshots/cutout/display-before.txt

result=0
if [[ "${MADRE_FIXES_7014_ACCEPTANCE:-false}" == "true" ]]; then
    ./gradlew :app:connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class=com.polinalinen.madre.FullscreenInstrumentedTest,com.polinalinen.madre.NavigationSmokeTest \
        --no-daemon --stacktrace --no-configuration-cache || result=$?
else
    ./gradlew connectedDebugAndroidTest --no-daemon --stacktrace --no-configuration-cache || result=$?
fi
# Preserve genuine failing-test evidence too; never turn a test failure into success.
adb pull /sdcard/googletest/test_outputfiles/screenshots screenshots/ || true
if ! adb pull /sdcard/Android/data/com.polinalinen.madre.qa/files/fs-evidence screenshots/cutout/; then
    if [[ "${MADRE_FIXES_7014_ACCEPTANCE:-false}" == "true" && "$result" == 0 ]]; then result=1; fi
fi
adb shell dumpsys window > screenshots/cutout/window-after.txt || true
exit "$result"
