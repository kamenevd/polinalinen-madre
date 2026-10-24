#!/usr/bin/env python3
"""Verify actual deliverable QA binary, never infer R8/profile/debuggable from Gradle flags."""
import argparse,hashlib,json,os,re,subprocess,zipfile
from pathlib import Path
from verify_apk_account_gate import read_apk_fields,validate_fields


def inspect(apk:Path,mapping:Path)->dict:
    sdk=Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    aapt=sdk/'build-tools'/os.environ.get('MADRE_BUILD_TOOLS_VERSION','36.0.0')/'aapt'
    badging=subprocess.check_output([str(aapt),'dump','badging',str(apk)],text=True)
    manifest=subprocess.check_output([str(aapt),'dump','xmltree',str(apk),'AndroidManifest.xml'],text=True)
    fields=read_apk_fields(apk)
    client=os.environ.get('MADRE_YANDEX_CLIENT_ID','')
    validate_fields(fields,client)
    assert fields.get('DEBUG') is False,'BuildConfig.DEBUG must be false'
    assert fields.get('QA_DIAGNOSTICS') is True,'QA diagnostics disappeared in R8'
    assert fields.get('BUILD_TYPE')=='qa','Not the actual deliverable qa variant'
    assert 'application-debuggable' not in badging,'Binary APK is debuggable'
    assert not re.search(r'android:debuggable[^\n]*0xffffffff',manifest),'Merged binary manifest enables debugging'
    assert "uses-permission: name='android.permission.VIBRATE'" in badging,'Actual APK lacks VIBRATE'
    assert 'yx'+client in manifest,'Approved SDK redirect absent from binary manifest'
    assert 'E: profileable' in manifest,'Actual QA is not profileable for owner diagnostics'
    assert re.search(r'android:shell[^\n]*0xffffffff',manifest),'Shell profileability missing'
    text=mapping.read_text()
    assert '# compiler: R8' in text,'No actual R8 mapping'
    renamed=sum(1 for line in text.splitlines() if re.match(r'^\S+ -> \S+:$',line) and line.split(' -> ')[0]!=line.split(' -> ')[1].rstrip(':'))
    assert renamed>0,'Mapping contains no actual obfuscation'
    with zipfile.ZipFile(apk) as archive:
        dex=[archive.read(n) for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex',n)]
        assert any(b'~~R8' in data for data in dex),'Actual DEX has no R8 compiler marker'
        assert all(b'RazvorotSceneActivity' not in data for data in dex),'Debug fixture Activity leaked into delivery'
        assert 'RazvorotSceneActivity' not in manifest,'Exported fixture Activity leaked into manifest'
        assets={n:archive.read(n) for n in ('assets/dexopt/baseline.prof','assets/dexopt/baseline.profm')}
        assert assets['assets/dexopt/baseline.prof'].startswith(b'pro\x00'),'Missing/invalid compiled ART profile'
        assert assets['assets/dexopt/baseline.profm'].startswith(b'prm\x00'),'Missing/invalid ART metadata'
    return {'head':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
        'package':fields['APPLICATION_ID'],'version':fields['VERSION_NAME'],'versionCode':fields['VERSION_CODE'],
        'variant':'qa','debuggable':False,'R8':True,'obfuscated_classes':renamed,'VIBRATE':True,
        'QA_DIAGNOSTICS':True,'account_gate':True,'approved_redirect':True,'debug_seeders_absent':True,
        'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'mapping_sha256':hashlib.sha256(mapping.read_bytes()).hexdigest(),
        'compiled_profile':{n:{'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()} for n,data in assets.items()},
        'physical_Huawei_acceptance':False,'profile_installation':'packaged; installation requires separate runtime evidence'}


def main():
    parser=argparse.ArgumentParser();parser.add_argument('apk',type=Path);parser.add_argument('--mapping',required=True,type=Path)
    args=parser.parse_args()
    try: print(json.dumps(inspect(args.apk,args.mapping),indent=2))
    except Exception as error:
        # Do not echo binary manifest, client values, DEX or tool stderr.
        print('QA BINARY VERIFICATION FAILED:',str(error) if isinstance(error,AssertionError) else type(error).__name__)
        return 1
    return 0

if __name__=='__main__':raise SystemExit(main())
