#!/usr/bin/env bash
# Paired fresh15/16 R8 measurements. Separate debug-mode controls already have their own raw run.
set -euo pipefail
root="$PWD"
before="$RUNNER_TEMP/madre-perf-before"
out="$root/performance-evidence"
mkdir -p "$out/before" "$out/after" "$out/profile" "$out/before-debug"
export ANDROID_SERIAL="$(adb get-serialno)"
case "$ANDROID_SERIAL" in emulator-*) ;; *) exit 1;; esac
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ]
free -m > "$out/host-memory-before.txt"
df -h > "$out/host-disk-before.txt"
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell wm size > "$out/device-size.txt"
adb shell wm density > "$out/device-density.txt"
adb shell getprop > "$out/device-properties.txt"
adb shell dumpsys SurfaceFlinger > "$out/renderer.txt"
cp "$before/benchmark-baseline-source.json" "$out/"
run_scroll() {
  local dir="$1" dest="$2"
  python3 "$root/scripts/perf_preflight.py" "$dir" "$dest"
  # No Gradle/JVM/UTP during timing or large trace transfers. Same actual source/APKs,
  # five iterations and route/foreground guards; a failed sample is never promoted to PASS.
  python3 - "$dir" "$dest" <<'PY'
import os,pathlib,re,shutil,subprocess,sys,time
root=pathlib.Path(sys.argv[1]);out=pathlib.Path(sys.argv[2])
apks=list((root/'macrobenchmark/build/outputs/apk/benchmark').glob('*.apk'));assert len(apks)==1
sdk=pathlib.Path(os.environ['ANDROID_HOME'])
badging=subprocess.check_output([str(sdk/'build-tools/36.0.0/aapt'),'dump','badging',str(apks[0])],text=True)
package=re.search(r"package: name='([^']+)'",badging).group(1)
assert package in ('com.polinalinen.madre.macrobenchmark','com.polinalinen.madre.macrobenchmark.test')
# Only this disposable CI driver is removed, after the prior arm's raw data were saved.
subprocess.run(['adb','uninstall',package],capture_output=True)
subprocess.run(['adb','install','-r','-t',str(apks[0])],check=True)
listing=subprocess.check_output(['adb','shell','pm','list','instrumentation'],text=True)
components=re.findall(r'instrumentation:(\S+)',listing)
component=next(c for c in components if c.startswith(package+'/'))
suppress='EMULATOR,DEBUGGABLE' if out.name=='before-debug' else 'EMULATOR'
command=['adb','shell','am','instrument','-w','-r','-e','class','com.polinalinen.madre.macrobenchmark.ScrollBenchmarks','-e','androidx.benchmark.suppressErrors',suppress,'-e','androidx.benchmark.output.enable','true',component]
result=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=1800)
(out/'instrumentation.txt').write_text(result.stdout)
time.sleep(2)
for attempt in range(3):
    # Remove only our incomplete host copy; the untouched original stays on this CI AVD.
    if (out/'raw').exists():shutil.rmtree(out/'raw')
    pull=subprocess.run(['adb','pull',f'/sdcard/Android/media/{package}',str(out/'raw')],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    (out/f'raw-pull-{attempt}.txt').write_text(pull.stdout)
    if pull.returncode==0:break
    subprocess.run(['adb','wait-for-device'],timeout=30,check=True)
else:raise RuntimeError('Actual raw trace transfer failed')
assert result.returncode==0 and 'OK (3 tests)' in result.stdout and 'FAILURES' not in result.stdout,'Actual three-route benchmark did not pass; inspect raw output'
assert len(list((out/'raw').rglob('*benchmarkData.json')))==1
assert len(list((out/'raw').rglob('*.perfetto-trace')))==15
print('Actual five-iteration three-route benchmark and all15 raw traces saved')
PY
  adb logcat -d > "$dest/actual-test-logcat.txt" || true
  adb pull /sdcard/Android/data/com.polinalinen.madre.macrobenchmark/files/route-failures "$dest/route-failures" || true
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
free -m > "$out/host-memory-after.txt"
df -h > "$out/host-disk-after.txt"
