#!/usr/bin/env python3
"""CI-only immutable14 functional source plus identical, explicit benchmark build/harness overlay.
Never writes the live checkout, canonical QA APKs, signing files or real user data.
"""
import hashlib,io,json,os,shutil,subprocess,sys,tarfile
from pathlib import Path
BASE=os.environ.get('MADRE_PERF_BASE','ba03ad926dcf19e66816f776ebac171313ec2e6b')
root=Path(__file__).resolve().parents[1]
target=Path(sys.argv[1]).resolve()
assert not target.exists(), 'Use a fresh CI scratch baseline, never reset an existing tree'
assert target.is_relative_to(Path(os.environ['RUNNER_TEMP']).resolve())
target.mkdir()
archive=subprocess.check_output(['git','archive',BASE],cwd=root)
with tarfile.open(fileobj=io.BytesIO(archive)) as t:t.extractall(target,filter='data')
def hashes():return {str(p.relative_to(target)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (target/'app/src/main').rglob('*') if p.is_file()}
before=hashes()
for name in ('build.gradle.kts','settings.gradle.kts'):shutil.copy2(root/name,target/name)
for name in ('macrobenchmark','app/src/benchmark'):
    destination=target/name
    if destination.exists():
        assert not destination.is_symlink() and destination.resolve().is_relative_to(target)
        shutil.rmtree(destination)  # our fresh isolated archive's admitted harness overlay only
    shutil.copytree(root/name,destination)
head=(root/'app/build.gradle.kts').read_text();p=target/'app/build.gradle.kts';text=p.read_text()
overlay=head[head.index('    // Deliverable QA:'):head.index('    compileOptions {')]
if os.environ.get('MADRE_BASE_DEBUG')=='true':
    needle='applicationIdSuffix=".benchmark"\n        isDebuggable=false'
    assert overlay.count(needle)==1
    overlay=overlay.replace(needle,'applicationIdSuffix=".benchmark"\n        isDebuggable=true\n        isMinifyEnabled=false\n        isShrinkResources=false')
if '    // Opt-in CI measurement only:' in text:
    start=text.index('    // Opt-in CI measurement only:');end=text.index('    compileOptions {',start)
    text=text[:start]+overlay+text[end:]
else:text=text.replace('    compileOptions {',overlay+'    compileOptions {',1)
source_overlay=head[head.index('        getByName("benchmark") {'):head.index('        getByName("androidTest") {')]
text=text.replace('    sourceSets {','    sourceSets {\n'+source_overlay,1)
text+='\ndependencies { implementation("androidx.profileinstaller:profileinstaller:1.4.1") }\n'
p.write_text(text)
# Only the debug fixture gains a deterministic scroll route. Production14 functions stay exact.
p=target/'app/src/debug/java/com/polinalinen/madre/ui/razvorot/RazvorotDebugScenes.kt';text=p.read_text()
needle='    val debug=remember{PaperDebugState()}'
assert text.count(needle)==1
if 'if(scene=="chapter-benchmark-scroll")chooseMode(ReadingMode.SCROLL)' not in text:
    text=text.replace(needle,'    remember(preferences,scene){if(scene=="chapter-benchmark-scroll")preferences.chooseMode(ReadingMode.SCROLL);true}\n'+needle,1)
p.write_text(text)
assert hashes()==before,'Immutable functional source/resources must remain byte-identical'
(target/'benchmark-baseline-source.json').write_text(json.dumps({'functional_source':BASE,'debug_comparison_arm':os.environ.get('MADRE_BASE_DEBUG')=='true','isolated_package':'com.polinalinen.madre.benchmark','overlay_only':['build.gradle.kts','settings.gradle.kts','app/build.gradle.kts','macrobenchmark/','app/src/benchmark/','debug-only scroll fixture'],'main_file_hashes':before},indent=2)+'\n')
print('Prepared immutable functional baseline:',BASE,'debug arm:',os.environ.get('MADRE_BASE_DEBUG')=='true','isolated:',target)
