#!/usr/bin/env bash
# Paired fresh15/16 R8 measurements. Separate debug-mode controls already have their own raw run.
set -euo pipefail
root="$PWD"
before="$RUNNER_TEMP/madre-perf-before"
out="$root/performance-evidence"
mkdir -p "$out/before" "$out/after" "$out/profile" "$out/before-debug"
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
  local extra=()
  if [[ "$dest" == "$out/before-debug" ]]; then extra=(-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,DEBUGGABLE); fi
  (cd "$dir" && ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.polinalinen.madre.macrobenchmark.ScrollBenchmarks \
    "${extra[@]}" \
    --no-daemon --stacktrace --no-configuration-cache) || result=$?
  cp -R "$dir/macrobenchmark/build/outputs" "$dest/" || true
  cp -R "$dir/macrobenchmark/build/reports" "$dest/" || true
  adb logcat -d > "$dest/actual-test-logcat.txt" || true
  adb pull /sdcard/Android/data/com.polinalinen.madre.macrobenchmark/files/route-failures "$dest/route-failures" || true
  return "$result"
}
if [[ -d "$RUNNER_TEMP/madre-perf-before-debug" ]]; then
  run_scroll "$RUNNER_TEMP/madre-perf-before-debug" "$out/before-debug"
fi
run_scroll "$before" "$out/before"
if [[ "${MADRE_PERF_COMPARE:-false}" == "true" ]]; then
  run_scroll "$root" "$out/after"
  # Both timed targets remain R8 with their genuine packed ART assets. CompilationMode.None
  # intentionally measures without claiming profile installation. The exact QA runtime lane
  # separately installs/verifies its packaged profile; no profile or R8 is disabled in the APK.
fi
