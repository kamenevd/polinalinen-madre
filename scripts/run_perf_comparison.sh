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
import hashlib,json,os,pathlib,re,subprocess,sys,time
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
logcat=subprocess.run(['adb','logcat','-d'],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
(out/'actual-test-logcat.txt').write_text(logcat.stdout)
time.sleep(2)
# Large directory SYNC pulls died at varying offsets without an adb error. Stream
# individual ORIGINAL files with the public exec-out API, then verify remote/local
# SHA256. A partial copy is not evidence and never replaces another complete file.
remote=f'/sdcard/Android/media/{package}/'
listing=subprocess.check_output(['adb','shell',f'for file in {remote}*; do printf "%s\\n" "$file"; done'],text=True)
(out/'remote-raw-index.txt').write_text(listing)
raw=out/'raw';raw.mkdir(exist_ok=True)
transfers=[]
def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()
for file in listing.splitlines():
    file=file.strip()
    assert file.startswith(remote)
    name=file[len(remote):]
    assert re.fullmatch(r'[-A-Za-z0-9_.]+',name),f'Unexpected raw file: {name}'
    expected=subprocess.check_output(['adb','shell','sha256sum',file],text=True).split()[0]
    assert re.fullmatch(r'[0-9a-f]{64}',expected)
    target=raw/name
    attempts=[]
    for attempt in range(3):
        with target.open('wb') as stream:
            copy=subprocess.run(['adb','exec-out','cat',file],stdout=stream,stderr=subprocess.PIPE,timeout=300)
        actual=sha(target)
        attempts.append({'returncode':copy.returncode,'stderr':copy.stderr.decode(errors='replace'),'bytes':target.stat().st_size,'sha256':actual})
        (out/'raw-transfer-index.json').write_text(json.dumps(transfers+[{'file':file,'remote_sha256':expected,'attempts':attempts}],indent=2))
        if copy.returncode==0 and actual==expected:break
        subprocess.run(['adb','wait-for-device'],timeout=30,check=True)
    else:raise RuntimeError(f'Actual raw file transfer failed checksum: {file}')
    transfers.append({'file':file,'remote_sha256':expected,'local_sha256':actual,'bytes':target.stat().st_size,'attempts':attempts})
(out/'raw-transfer-index.json').write_text(json.dumps(transfers,indent=2))
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
