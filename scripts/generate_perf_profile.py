#!/usr/bin/env python3
"""CI AVD only; generate genuine unobfuscated ART rules and retain raw instrumentation output."""
import re,subprocess,sys
from pathlib import Path
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
# Connected-test cleanup may remove the self-instrumenting benchmark APK.
apks=list(Path('macrobenchmark/build/outputs/apk/benchmark').glob('*.apk'))
assert len(apks)==1,'Expected one real benchmark instrumentation APK'
subprocess.run(['adb','install','-r',str(apks[0])],check=True)
listing=subprocess.check_output(['adb','shell','pm','list','instrumentation'],text=True)
components=re.findall(r'instrumentation:(\S+)',listing)
component=next(c for c in components if c.startswith('com.polinalinen.madre.macrobenchmark/') or c.startswith('com.polinalinen.madre.macrobenchmark.test/'))
package=component.split('/')[0]
command=['adb','shell','am','instrument','-w','-e','class','com.polinalinen.madre.macrobenchmark.GenerateReadingProfile','-e','androidx.benchmark.suppressErrors','EMULATOR',component]
r=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
(out/'instrumentation.txt').write_text(r.stdout)
assert r.returncode==0 and 'OK (1 test)' in r.stdout,'Real baseline profile instrumentation failed; inspect raw output'
subprocess.run(['adb','pull',f'/sdcard/Android/media/{package}',str(out/'raw')],check=True)
profiles=list((out/'raw').rglob('*baseline-prof.txt'))
assert profiles and all(p.stat().st_size>0 for p in profiles),'No genuine generated baseline profile'
print('Real BaselineProfileRule rules:',*[str(p) for p in profiles])
