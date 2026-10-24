#!/usr/bin/env python3
"""Fail-fast real release-like CI launch; retain actual platform crash evidence, never fake metrics."""
import hashlib,json,subprocess,sys,time,zipfile
from pathlib import Path
root=Path(sys.argv[1]);out=Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
r=subprocess.run([str(root/'gradlew'),':app:assembleBenchmark','--no-daemon','--stacktrace','--no-configuration-cache'],cwd=root)
assert r.returncode==0,'Cannot profile a failed release-like build'
apks=list((root/'app/build/outputs/apk/benchmark').glob('*.apk'));assert len(apks)==1
with zipfile.ZipFile(apks[0]) as apk:
    descriptor=b'Lcom/polinalinen/madre/ui/razvorot/RazvorotSceneActivity;'
    assert any(descriptor in apk.read(n) for n in apk.namelist() if n.startswith('classes') and n.endswith('.dex')),'Real benchmark Activity absent from APK DEX; check AGP built-in Kotlin source directories'
    if (root/'app/src/main/baseline-prof.txt').is_file():
        assets={n:apk.read(n) for n in apk.namelist() if n.startswith('assets/dexopt/')}
        assert assets.get('assets/dexopt/baseline.prof'),'Generated app profile was not compiled into the actual release-like APK'
        (out/'compiled-art-profile.json').write_text(json.dumps({n:{'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()} for n,data in assets.items()},indent=2)+'\n')
subprocess.run(['adb','install','-r',str(apks[0])],check=True)
subprocess.run(['adb','logcat','-c'],check=True)
launch=subprocess.run(['adb','shell','am','start','-W','-n','com.polinalinen.madre.benchmark/com.polinalinen.madre.ui.razvorot.RazvorotSceneActivity','--es','scene','toc'],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
(out/'preflight-launch.txt').write_text(launch.stdout)
time.sleep(2)
for name,command in [('preflight-logcat.txt',['adb','logcat','-d']),('preflight-package.txt',['adb','shell','dumpsys','package','com.polinalinen.madre.benchmark']),('preflight-window.txt',['adb','shell','dumpsys','window']),('preflight-gfxinfo.txt',['adb','shell','dumpsys','gfxinfo','com.polinalinen.madre.benchmark','framestats'])]:
 r=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT);(out/name).write_text(r.stdout)
r=subprocess.run(['adb','shell','pidof','com.polinalinen.madre.benchmark'],text=True,capture_output=True)
assert r.returncode==0 and r.stdout.strip(),'Actual minified benchmark process did not survive launch; inspect preflight-logcat.txt'
print('Real non-debuggable benchmark target survives launch; PID:',r.stdout.strip())
