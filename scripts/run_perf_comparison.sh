#!/usr/bin/env bash
# Single emulator runner command. Required15 measurement, NOT a Huawei hardware claim.
set -euo pipefail
root="$PWD"
before="$RUNNER_TEMP/madre-perf-before"
out="$root/performance-evidence"
mkdir -p "$out/before" "$out/after" "$out/profile"
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell wm size > "$out/device-size.txt"
adb shell wm density > "$out/device-density.txt"
adb shell getprop > "$out/device-properties.txt"
adb shell dumpsys SurfaceFlinger > "$out/renderer.txt"
cp "$before/benchmark-baseline-source.json" "$out/"
run_scroll() {
  local dir="$1" dest="$2"
  python3 "$root/scripts/perf_preflight.py" "$dir" "$dest"
  local result=0
  (cd "$dir" && ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.polinalinen.madre.macrobenchmark.ScrollBenchmarks \
    --no-daemon --stacktrace --no-configuration-cache) || result=$?
  cp -R "$dir/macrobenchmark/build/outputs" "$dest/" || true
  cp -R "$dir/macrobenchmark/build/reports" "$dest/" || true
  adb logcat -d > "$dest/actual-test-logcat.txt" || true
  return "$result"
}
run_scroll "$before" "$out/before"
if [[ "${MADRE_PERF_COMPARE:-false}" == "true" ]]; then
  run_scroll "$root" "$out/after"
  # Generate un-obfuscated real ART rules from the same15 production journeys.
  # Timing itself above remains R8 release-like for BOTH14 and15.
  ./gradlew :app:assembleNonMinifiedBenchmark --no-daemon --stacktrace --no-configuration-cache
  adb install -r app/build/outputs/apk/nonMinifiedBenchmark/app-nonMinifiedBenchmark.apk
  python3 scripts/generate_perf_profile.py "$out/profile"
fi
