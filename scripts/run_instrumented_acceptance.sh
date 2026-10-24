#!/usr/bin/env bash
# CI emulator only. Keep this a single action command: the pinned emulator runner
# splits its `script` input into independent `sh -c` lines (no multiline if/trap scope).
set -euo pipefail
mkdir -p screenshots/cutout
# Keep this emulator's physical profile/density. The separate JVM key PNGs remain
# 412x915; forced logical sizes on the generic320x640 AVD did not expose a cutout.
adb shell wm size
adb shell wm density
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell cmd overlay enable com.android.internal.display.cutout.emulation.tall
adb shell cmd overlay set-priority com.android.internal.display.cutout.emulation.tall highest
adb shell cmd overlay lookup android android:string/config_mainBuiltInDisplayCutout > screenshots/cutout/resolved-path.txt
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
if ! adb pull /sdcard/googletest/test_outputfiles/screenshots screenshots/; then
    if [[ "${MADRE_FIXES_7014_ACCEPTANCE:-false}" == "true" && "$result" == 0 ]]; then result=1; fi
fi
if [[ "${MADRE_FIXES_7014_ACCEPTANCE:-false}" == "true" ]]; then
    test -s screenshots/screenshots/fs-evidence/fs6-insets.txt || result=1
    test -s screenshots/screenshots/fs-evidence/fs6-after.png || result=1
fi
adb shell dumpsys window > screenshots/cutout/window-after.txt || true
exit "$result"
