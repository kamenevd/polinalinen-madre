#!/usr/bin/env python3
"""Exact frozen payload upgrade on ONE CI AVD; no target build/repackage/flag mutation.

--prepare checks provenance/binaries and the pinned AndroidX source before AVD boot.
Runtime uses only adb + the prebuilt external self-instrumenting driver. All original
JSON/traces survive even a failed arm. Ordinary QA/minified/legacy are separate lanes.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request
import zipfile

PACKAGE = 'com.polinalinen.madre.qa'
DRIVER = 'com.polinalinen.madre.macrobenchmark'
CLASS = DRIVER + '.ExactQaTiming'
BEFORE_HEAD = 'ef9fc07f5591b755faaea32cf6430dc1d6b828e9'
BEFORE_RUN = 40096655873
BEFORE_SHA = '0dc7d487df53ab56e9cea41ddd3609be10f6a0fa38f341ef7574169dbc9bf6b3'
CERT = '9ACA5F5F57F301EC8359AD670ECEB6387156132007C07FF36A31266326468258'
SOURCE_SHA = '7eee75b0a5982329906d2ae074c5cc05e4d25500a35d580a3401bb97dbfce2c4'
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'performance-evidence/exact-qa-timing'


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def run(args, *, log=None, check=True, timeout=300):
    result = subprocess.run([str(a) for a in args], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout)
    if log is not None:
        log.write_text(result.stdout)
    if check and result.returncode:
        raise RuntimeError(f'Command failed ({result.returncode}): {args[0:4]}; see raw log')
    return result


def one(root, glob):
    files = list(Path(root).rglob(glob))
    assert len(files) == 1, f'Expected exactly one {glob}: {len(files)}'
    return files[0]


def inspect(apk, version, debug, dest):
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    aapt = sdk / 'build-tools' / os.environ.get('MADRE_BUILD_TOOLS_VERSION', '36.0.0') / 'aapt'
    badging = run([aapt, 'dump', 'badging', apk], log=dest / 'apk-badging.txt').stdout
    manifest = run([aapt, 'dump', 'xmltree', apk, 'AndroidManifest.xml'], log=dest / 'binary-manifest.txt').stdout
    identity = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    assert identity and identity.groups() == (PACKAGE, str(version), '7.0.15' if debug else '7.0.16')
    assert ('application-debuggable' in badging) == debug, 'Unexpected actual debug flag'
    if not debug:
        assert 'E: profileable' in manifest and re.search(r'android:shell[^\n]*0xffffffff', manifest)
    else:
        assert 'E: profileable' not in manifest, 'Issued15 manifest differs from frozen payload'
    run(['python3', ROOT / 'scripts/verify_apk_signature.py', apk, '--expected', CERT], log=dest / 'signature.txt')
    return {'package': PACKAGE, 'versionCode': version, 'debuggable': debug,
            'profileable_shell': not debug, 'apk_sha256': sha(apk)}


def prepare():
    OUT.mkdir(parents=True, exist_ok=True)
    head = run(['git', 'rev-parse', 'HEAD']).stdout.strip()
    before = one(os.environ['MADRE_ISSUED15_DIR'], '*.apk')
    assert sha(before) == BEFORE_SHA, 'Not the original issued15 bytes'
    after = one(os.environ['MADRE_EXACT_QA_DIR'], 'app-qa.apk')
    proof = one(os.environ['MADRE_EXACT_QA_DIR'], 'qa-binary-provenance.json')
    mapping = one(os.environ['MADRE_EXACT_QA_DIR'], 'mapping.txt')
    provenance = json.loads(proof.read_text())
    assert provenance['head'] == head and provenance['apk_sha256'] == sha(after)
    assert provenance['mapping_sha256'] == sha(mapping)
    assert provenance['variant'] == 'qa' and provenance['R8'] is True and provenance['debuggable'] is False
    assert provenance['versionCode'] == 52
    # Actual R8/DEX/assets/manifest verification, not merely the artifact label.
    run(['python3', ROOT / 'scripts/verify_qa_release_like.py', after, '--mapping', mapping], log=OUT / 'after-binary-verification.json')
    fixture = one(os.environ['MADRE_EXACT_QA_TEST_DIR'], '*.apk')
    driver = one(ROOT / 'macrobenchmark/build/outputs/apk/benchmark', '*.apk')
    controls = {'head': head, 'run_id': os.environ.get('GITHUB_RUN_ID'),
                'before_source_head': BEFORE_HEAD, 'before_artifact_run': BEFORE_RUN,
                'comparison': 'actual issued debug15 vs same-run ordinary R8 QA16 upgrade; NOT isolated renderer/AOT gain',
                'device': 'single CI API35 AVD; not physical Huawei acceptance',
                'compilation': 'CompilationMode.None both arms (runtime dexopt reset; target APK/profile assets unchanged)',
                'iterations': 5, 'routes': ['mainActivityFrames', 'ovenScroll', 'tocScroll'],
                'self_instrumenting': True, 'production_activity': 'com.polinalinen.madre.MainActivity',
                'target_builds_during_timing': False, 'payloads': {}}
    for arm, apk, version, debug in [('before15', before, 51, True), ('after16', after, 52, False)]:
        dest = OUT / arm
        dest.mkdir(exist_ok=True)
        shutil.copy2(apk, dest / 'target.apk')
        controls['payloads'][arm] = inspect(dest / 'target.apk', version, debug, dest)
        controls['payloads'][arm]['suppressErrors'] = 'EMULATOR,DEBUGGABLE' if debug else 'EMULATOR'
    shutil.copy2(proof, OUT / 'qa-binary-provenance.json')
    shutil.copy2(driver, OUT / 'driver.apk')
    shutil.copy2(fixture, OUT / 'fixture.apk')
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    aapt = sdk / 'build-tools' / os.environ.get('MADRE_BUILD_TOOLS_VERSION', '36.0.0') / 'aapt'
    driver_badging = run([aapt, 'dump', 'badging', OUT / 'driver.apk'], log=OUT / 'driver-badging.txt').stdout
    driver_identity = re.search(r"package: name='([^']+)'", driver_badging)
    assert driver_identity, 'Prebuilt driver has no binary package identity'
    driver_package = driver_identity.group(1)
    assert driver_package in (DRIVER, DRIVER + '.test')
    controls['driver_package'] = driver_package
    run([aapt, 'dump', 'xmltree', OUT / 'driver.apk', 'AndroidManifest.xml'], log=OUT / 'driver-binary-manifest.txt')
    for name in ['driver', 'fixture']:
        controls[name + '_sha256'] = sha(OUT / (name + '.apk'))
    sources = OUT / 'benchmark-macro-1.4.1-sources.jar'
    urllib.request.urlretrieve('https://dl.google.com/dl/android/maven2/androidx/benchmark/benchmark-macro/1.4.1/benchmark-macro-1.4.1-sources.jar', sources)
    assert sha(sources) == SOURCE_SHA, 'AndroidX exact-version source changed'
    with zipfile.ZipFile(sources) as archive:
        text = archive.read(next(n for n in archive.namelist() if n.endswith('/Macrobenchmark.kt'))).decode()
    assert re.search(r'Build.VERSION.SDK_INT in 29\.\.30 &&\s*applicationInfo.isNotProfileableByShell', text)
    (OUT / 'Macrobenchmark-1.4.1.kt').write_text(text)
    controls['androidx_source_sha256'] = SOURCE_SHA
    # Record the actual locally resolved implementation too, not only a source URL.
    aar = one(Path.home() / '.gradle/caches/modules-2/files-2.1/androidx.benchmark/benchmark-macro/1.4.1', '*.aar')
    controls['resolved_benchmark_macro_aar_sha256'] = sha(aar)
    write_json(OUT / 'controls.json', controls)


def adb(*args, **kwargs):
    return run(['adb', *args], **kwargs)


def guard_serial(serial):
    assert serial.startswith('emulator-') and adb('get-serialno').stdout.strip() == serial
    assert adb('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() == '1'
    assert adb('shell', 'getprop', 'ro.build.version.sdk').stdout.strip() == '35'


def fixture_phase(phase, dest):
    adb('shell', 'am', 'force-stop', PACKAGE)
    result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'phase', phase,
                 PACKAGE + '.test/com.polinalinen.madre.MinifiedPlanInstrumentation', log=dest / (phase + '.txt'))
    assert 'INSTRUMENTATION_RESULT: minifiedPersistence=PASS' in result.stdout
    assert 'INSTRUMENTATION_CODE: -1' in result.stdout and 'minifiedPersistence=FAIL' not in result.stdout
    adb('pull', '/sdcard/Android/data/' + PACKAGE + '/files/minified-delivery', dest / 'fixture-raw')


def collect_raw(dest, driver):
    # Same original-file exec-out + remote/local checksum strategy as run_perf_comparison.sh.
    remote = '/sdcard/Android/media/' + driver + '/'
    listing = adb('shell', f'find {remote} -maxdepth 1 -type f').stdout.splitlines()
    (dest / 'remote-raw-index.txt').write_text('\n'.join(listing) + '\n')
    raw = dest / 'raw'
    raw.mkdir(exist_ok=True)
    index = []
    for file in listing:
        name = file.removeprefix(remote)
        assert file.startswith(remote) and re.fullmatch(r'[-A-Za-z0-9_.]+', name)
        expected = adb('shell', 'sha256sum', file).stdout.split()[0]
        assert re.fullmatch(r'[0-9a-f]{64}', expected)
        target = raw / name
        attempts = []
        for _ in range(3):
            with target.open('wb') as stream:
                result = subprocess.run(['adb', 'exec-out', 'cat', file], stdout=stream, stderr=subprocess.PIPE, timeout=300)
            actual = sha(target)
            attempts.append({'exit_code': result.returncode, 'sha256': actual, 'bytes': target.stat().st_size})
            write_json(dest / 'raw-transfer-index.json', index + [{'file': file, 'remote_sha256': expected, 'attempts': attempts}])
            if result.returncode == 0 and actual == expected:
                break
            adb('wait-for-device', timeout=30)
        else:
            raise AssertionError('Original raw transfer checksum failed: ' + file)
        index.append({'file': file, 'remote_sha256': expected, 'local_sha256': actual, 'attempts': attempts})
    write_json(dest / 'raw-transfer-index.json', index)


def validate_raw(dest):
    data = json.loads(one(dest / 'raw', '*benchmarkData.json').read_text())
    benches = data['benchmarks']
    assert len(benches) == 3
    assert {b['name'].removeprefix('EMULATOR_').removeprefix('DEBUGGABLE_') for b in benches} == {'mainActivityFrames', 'ovenScroll', 'tocScroll'}
    assert len(list((dest / 'raw').glob('*.perfetto-trace'))) == 15
    for b in benches:
        assert b['className'] == CLASS and b['repeatIterations'] == 5
        assert len(b['metrics']['frameCount']['runs']) == 5 and all(n > 0 for n in b['metrics']['frameCount']['runs'])
        for metric in ['frameDurationCpuMs', 'frameOverrunMs']:
            samples = b['sampledMetrics'][metric]['runs']
            assert len(samples) == 5 and all(samples), 'Missing actual FrameTimingMetric samples'
        traces = b['profilerOutputs']
        assert len(traces) == 5
        for trace in traces:
            assert trace['type'] == 'PerfettoTrace' and (dest / 'raw' / trace['filename']).stat().st_size > 0


def runtime():
    controls = json.loads((OUT / 'controls.json').read_text())
    assert controls['head'] == run(['git', 'rev-parse', 'HEAD']).stdout.strip()
    driver = controls['driver_package']
    assert driver in (DRIVER, DRIVER + '.test')
    serial = os.environ['ANDROID_SERIAL']
    guard_serial(serial)
    controls['emulator_serial'] = serial
    write_json(OUT / 'controls.json', controls)
    assert not re.search(r'^package:' + re.escape(PACKAGE) + r'\s*$', adb('shell', 'pm', 'list', 'packages', PACKAGE).stdout, re.M), 'Exact upgrade must begin before ANY QA16 install; no downgrade/uninstall workaround'
    for name in ['driver', 'fixture']:
        assert sha(OUT / (name + '.apk')) == controls[name + '_sha256']
    adb('install', '-r', '-t', OUT / 'driver.apk', log=OUT / 'install-driver.txt')
    listing = adb('shell', 'pm', 'list', 'instrumentation').stdout
    (OUT / 'instrumentations.txt').write_text(listing)
    matches = re.findall(r'instrumentation:(\S+) \(target=([^\)]+)\)', listing)
    component = next(c for c, target in matches if c.startswith(driver + '/') and target == driver)
    failed = False
    for arm in ['before15', 'after16']:
        dest = OUT / arm
        guard_serial(serial)
        assert sha(dest / 'target.apk') == controls['payloads'][arm]['apk_sha256']
        # -r only. Never -d, uninstall, clear data, repackage, or rebuild either target.
        adb('install', '-r', dest / 'target.apk', log=dest / 'install.txt')
        installed = adb('shell', 'pm', 'path', PACKAGE).stdout.strip().removeprefix('package:')
        assert installed.startswith('/data/app/') and '\n' not in installed
        assert adb('shell', 'sha256sum', installed).stdout.split()[0] == sha(dest / 'target.apk')
        adb('shell', 'dumpsys', 'package', PACKAGE, log=dest / 'installed-package-before.txt')
        adb('shell', 'getprop', log=dest / 'device-properties.txt')
        adb('shell', 'dumpsys', 'SurfaceFlinger', log=dest / 'renderer.txt')
        adb('shell', 'wm', 'size', log=dest / 'size.txt')
        adb('shell', 'wm', 'density', log=dest / 'density.txt')
        adb('shell', 'settings', 'get', 'global', 'window_animation_scale', log=dest / 'animation-scale.txt')
        adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
        if arm == 'before15':
            adb('install', '-r', '-t', OUT / 'fixture.apk', log=OUT / 'install-fixture.txt')
            fixture_phase('exact-timing-seed-15', dest)
        else:
            fixture_phase('exact-timing-check-16', dest)  # prove real preserved data BEFORE measuring; no reseed
        adb('shell', 'rm', '-rf', '/sdcard/Android/media/' + driver)  # disposable DRIVER raw only
        adb('shell', 'rm', '-rf', '/sdcard/Android/data/' + driver + '/files/exact-qa-routes')
        command = ['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', CLASS,
                   '-e', 'madre.exactQaArm', arm, '-e', 'androidx.benchmark.suppressErrors', controls['payloads'][arm]['suppressErrors'],
                   '-e', 'androidx.benchmark.output.enable', 'true', component]
        write_json(dest / 'command.json', command)
        try:
            result = run(command, log=dest / 'instrumentation.txt', check=False, timeout=2400)
        finally:
            adb('logcat', '-d', log=dest / 'logcat.txt', check=False)
            collect_raw(dest, driver)
            adb('pull', '/sdcard/Android/data/' + driver + '/files/exact-qa-routes', dest / 'routes', check=False)
            adb('shell', 'dumpsys', 'package', PACKAGE, log=dest / 'installed-package-after.txt', check=False)
        fixture_phase('exact-timing-check-' + ('15' if arm == 'before15' else '16'), dest)
        try:
            assert result.returncode == 0 and 'OK (3 tests)' in result.stdout and 'FAILURES' not in result.stdout
            validate_raw(dest)
            write_json(dest / 'validation.json', {'actual_raw_complete': True, 'iterations_per_route': 5, 'traces': 15})
        except (AssertionError, KeyError, ValueError) as error:
            failed = True
            write_json(dest / 'validation.json', {'actual_raw_complete': False, 'failure': str(error)})
    guard_serial(serial)
    assert (OUT / 'before15/size.txt').read_bytes() == (OUT / 'after16/size.txt').read_bytes()
    assert (OUT / 'before15/density.txt').read_bytes() == (OUT / 'after16/density.txt').read_bytes()
    assert not failed, 'Exact arm failed; original evidence retained; later independent lanes still run'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepare', action='store_true')
    args = parser.parse_args()
    if args.prepare:
        prepare()
    else:
        try:
            runtime()
        finally:
            write_json(OUT / 'checksums.json', {str(p.relative_to(OUT)): sha(p) for p in sorted(OUT.rglob('*')) if p.is_file() and p.name != 'checksums.json'})


if __name__ == '__main__':
    main()
