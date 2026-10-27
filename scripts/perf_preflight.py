#!/usr/bin/env python3
"""Fail-fast real release-like CI launch; retain actual platform crash evidence, never fake metrics."""
import hashlib,json,os,shutil,subprocess,sys,time,zipfile
from pathlib import Path
root=Path(sys.argv[1]);out=Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
if os.environ.get('MADRE_PERF_PREBUILT')!='true':
    r=subprocess.run([str(root/'gradlew'),':app:assembleBenchmark','--no-daemon','--stacktrace','--no-configuration-cache'],cwd=root)
    assert r.returncode==0,'Cannot profile a failed release-like build'
apks=list((root/'app/build/outputs/apk/benchmark').glob('*.apk'));assert len(apks)==1
with zipfile.ZipFile(apks[0]) as apk:
    descriptor=b'Lcom/polinalinen/madre/ui/razvorot/RazvorotSceneActivity;'
    assert any(descriptor in apk.read(n) for n in apk.namelist() if n.startswith('classes') and n.endswith('.dex')),'Real benchmark Activity absent from APK DEX; check AGP built-in Kotlin source directories'
    r8=any(b'~~R8' in apk.read(n) for n in apk.namelist() if n.startswith('classes') and n.endswith('.dex'))
    if (root/'app/src/main/baseline-prof.txt').is_file():
        assets={n:apk.read(n) for n in apk.namelist() if n.startswith('assets/dexopt/')}
        assert assets.get('assets/dexopt/baseline.prof'),'Generated app profile was not compiled into the actual release-like APK'
        (out/'compiled-art-profile.json').write_text(json.dumps({n:{'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()} for n,data in assets.items()},indent=2)+'\n')
sdk=Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
badging=subprocess.check_output([str(sdk/'build-tools'/'36.0.0'/'aapt'),'dump','badging',str(apks[0])],text=True)
debuggable='application-debuggable' in badging
source_file=root/'benchmark-baseline-source.json'
source=json.loads(source_file.read_text()) if source_file.exists() else {'functional_source':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()}
debug_arm=source.get('debug_comparison_arm',False)
assert debuggable==debug_arm and r8!=debug_arm,'Actual APK does not match the declared debug/release-like comparison arm'
(out/'actual-apk-controls.json').write_text(json.dumps({'head':source['functional_source'],'apk_sha256':hashlib.sha256(apks[0].read_bytes()).hexdigest(),'variant':'benchmark','debuggable':debuggable,'R8':r8,'compilation':'CompilationMode.None','iterations_per_route':5,'API':subprocess.check_output(['adb','shell','getprop','ro.build.version.sdk'],text=True).strip(),'renderer':'see renderer.txt; no physical Huawei claim','profile_packed':(root/'app/src/main/baseline-prof.txt').exists()},indent=2)+'\n')
shutil.copy2(apks[0],out/'measured-target.apk')
build_source=out/'build-source';build_source.mkdir(exist_ok=True)
for relative in ('app/build.gradle.kts','app/proguard-rules.pro','build.gradle.kts','settings.gradle.kts','gradle.properties','app/build/outputs/mapping/benchmark/mapping.txt'):
    source_path=root/relative
    if source_path.is_file():
        target=build_source/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source_path,target)
subprocess.run(['adb','install','-r',str(apks[0])],check=True)
subprocess.run(['adb','logcat','-c'],check=True)
launch=subprocess.run(['adb','shell','am','start','-W','-n','com.polinalinen.madre.benchmark/com.polinalinen.madre.ui.razvorot.RazvorotSceneActivity','--es','scene','toc'],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
(out/'preflight-launch.txt').write_text(launch.stdout)
time.sleep(2)
with (out/'preflight-diagnostic-not-acceptance.png').open('wb') as screenshot:
 subprocess.run(['adb','exec-out','screencap','-p'],stdout=screenshot,check=True)
for name,command in [('preflight-logcat.txt',['adb','logcat','-d']),('preflight-package.txt',['adb','shell','dumpsys','package','com.polinalinen.madre.benchmark']),('preflight-window.txt',['adb','shell','dumpsys','window']),('preflight-gfxinfo.txt',['adb','shell','dumpsys','gfxinfo','com.polinalinen.madre.benchmark','framestats'])]:
 r=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT);(out/name).write_text(r.stdout)
r=subprocess.run(['adb','shell','pidof','com.polinalinen.madre.benchmark'],text=True,capture_output=True)
assert r.returncode==0 and r.stdout.strip(),'Actual minified benchmark process did not survive launch; inspect preflight-logcat.txt'
print('Measured target survives launch; debuggable:',debuggable,'PID:',r.stdout.strip())
