"""Validate exact captured system Binder exports, never query a live phone Binder."""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import subprocess

root=Path(__file__).resolve().parent.parent
out=root/'build/calls-sms-validation/usable/phone-anchor'
metadata=json.loads((out/'device-metadata.json').read_text('utf-8-sig'))
nm=Path('C:/Users/tycykp/AppData/Local/Android/Sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe')
expected={'AServiceManager_checkService','AIBinder_toJavaBinder','AIBinder_decStrong'}
records=[]
for item in metadata['records']:
    library=Path(item['library']); sha=hashlib.sha256(library.read_bytes()).hexdigest()
    assert sha==item['librarySha256']
    result=subprocess.run([str(nm),'--dynamic','--defined-only',str(library)],capture_output=True,text=True,check=True)
    lines=[s for s in result.stdout.splitlines() if s.split() and s.split()[-1].split('@@')[0] in expected]
    assert {s.split()[-1].split('@@')[0] for s in lines} == expected
    records.append({'serial':item['serial'],'library_sha256':sha,'exports':lines})
signer=(out/'oplus-phone-apksigner.txt').read_text('utf-8-sig')
certificate='e49802409584ce53152a9000820a51e4fa8a723b7bcc263e335240acf100bf9e'
assert 'Verifies' in signer and certificate in signer
output={'utc':datetime.now(timezone.utc).isoformat(),'command':'python scripts/verify-telephony-system-exports.py',
        'script_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'native_command':'llvm-nm.exe --dynamic --defined-only <fixed captured libbinder_ndk.so> (both devices, select exact symbol names)',
        'scope':'Original system library exports and saved signer evidence only; not runtime access, UID/PID proof, call or audio action',
        'records':records,'oplus_phone_signer_sha256':certificate,
        'signer_evidence_sha256':hashlib.sha256((out/'oplus-phone-apksigner.txt').read_bytes()).hexdigest()}
(out/'system-exports.json').write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS: exact three required system Binder exports on each device and saved phone APK signer; no device action')
