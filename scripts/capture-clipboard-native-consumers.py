"""Package/APK-only native consumer inventory, no app launch, clipboard read or user-file access."""
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/clipboard-validation/native-consumers'
TASK = '01a1bbe4-d754-7773-85dd-5cd0f1ce029f'


def adb(serial, *args):
    return subprocess.run(['adb','-s',serial,*args],capture_output=True,timeout=90)


def main():
    assert TASK in (ROOT/'build/coordination-locks/devices.lock').read_text(encoding='utf-8-sig')
    OUT.mkdir(parents=True, exist_ok=True)
    records = {}
    for serial, package in [('b59f8656','com.android.fileexplorer'),('3B161Q00YN200000','com.coloros.filemanager')]:
        rows = adb(serial,'shell','pm','path',package)
        paths = [m.group(1) for line in rows.stdout.decode('utf-8','replace').splitlines()
                 if (m:=re.fullmatch(r'package:(/[A-Za-z0-9_+=~./-]+\.apk)',line.strip()))]
        if not paths:
            records[serial] = dict(package=package,installed=False)
            continue
        base = next((p for p in paths if p.endswith('/base.apk')), paths[0])
        directory = OUT/serial
        directory.mkdir(exist_ok=True)
        destination = directory/(package+'.apk')
        pulled = adb(serial,'pull',base,str(destination))
        if pulled.returncode:
            raise RuntimeError('Native APK pull failed: '+serial)
        resolved = adb(serial,'shell','cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN',
                       '-c','android.intent.category.LAUNCHER','-p',package)
        components = [line.strip() for line in resolved.stdout.decode('utf-8','replace').splitlines()
                      if re.fullmatch(re.escape(package)+r'/[A-Za-z0-9_.$]+',line.strip())]
        records[serial] = dict(package=package,installed=True,base_apk=base,local_apk=str(destination.relative_to(ROOT)),
            apk_sha256=hashlib.sha256(destination.read_bytes()).hexdigest(),launcher=components[-1] if components else None)
    evidence = dict(command='powershell -NoProfile -File scripts/capture-clipboard-native-consumers.ps1',
        source_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in
                       [Path(__file__),ROOT/'scripts/capture-clipboard-native-consumers.ps1']},records=records,
        scope='Read-only package/APK metadata; no consumer execution or business acceptance')
    (OUT/'inventory.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Native consumer package/APK inventory recorded; no UI or clipboard action')


if __name__=='__main__':
    main()
