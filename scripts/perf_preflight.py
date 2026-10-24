#!/usr/bin/env python3
"""Fail-fast real release-like CI launch; retain actual platform crash evidence, never fake metrics."""
import subprocess,sys,time
from pathlib import Path
root=Path(sys.argv[1]);out=Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
r=subprocess.run([str(root/'gradlew'),':app:assembleBenchmark','--no-daemon','--stacktrace','--no-configuration-cache'],cwd=root)
assert r.returncode==0,'Cannot profile a failed release-like build'
apks=list((root/'app/build/outputs/apk/benchmark').glob('*.apk'));assert len(apks)==1
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
