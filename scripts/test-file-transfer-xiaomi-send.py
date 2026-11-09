"""Host-only production hook exercise using isolated Android/OEM call fixtures.

Production code is separately compiled against the real Android SDK/libxposed by
test-file-transfer.ps1. These fixtures never enter Gradle or an APK, and do not
provide native/device success evidence.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
bridge_only = '--bridge-only' in sys.argv
method = next((arg.split('=',1)[1] for arg in sys.argv if arg.startswith('--method=')),None)
suffix = ('-screen' if '--screen' in sys.argv else '') + ('-'+method if method else '')
out = root / (('build/file-transfer-validation/bridge' if bridge_only else 'build/file-transfer-validation/xiaomi-send-hooks')+suffix)
out.mkdir(parents=True, exist_ok=True)
jdk = Path('C:/Program Files/Android/Android Studio/jbr/bin')
cache = Path.home() / '.gradle/caches'
xposed = next((cache / '9.7.1/transforms').rglob('api-102.0.0-api.jar'))
junit = next((cache / 'modules-2/files-2.1/junit/junit/4.13.2').rglob('*.jar'))
hamcrest = next((cache / 'modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3').rglob('*.jar'))
android = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platforms/android-37.0/android.jar'
production = root / 'build/file-transfer-validation/classes'
fixtures = sorted((root / 'scripts/file-transfer-fixtures').rglob('*.java'))
classes = out / 'classes'
classes.mkdir(exist_ok=True)
cp = os.pathsep.join(map(str, [production, android, xposed, junit, hamcrest]))
test_class = 'io.github.yuejie.connect.FileTransferBridgeCheck' if bridge_only else ('io.github.yuejie.connect.FileTransferScreenSendHooksCheck' if '--screen' in sys.argv else 'io.github.yuejie.connect.FileTransferXiaomiSendHooksCheck')
runner = ['io.github.yuejie.connect.FileTransferMethodCheck',test_class,method] if method else ['org.junit.runner.JUnitCore',test_class]
commands = [
    [str(jdk / 'javac.exe'), '--release', '17', '-encoding', 'UTF-8', '-cp', cp, '-d', str(classes), *map(str, fixtures)],
    [str(jdk / 'java.exe'), '-cp', os.pathsep.join([str(classes), cp]), *runner],
]
records = []
for command in commands:
    run = subprocess.run(command, cwd=root, text=True, encoding='utf-8', errors='replace', capture_output=True)
    records.append(dict(command=command, exit_code=run.returncode, stdout=run.stdout, stderr=run.stderr))
    print(run.stdout, end='')
    if run.stderr:
        print(run.stderr, file=sys.stderr, end='')
    if run.returncode:
        break
sources = sorted((root / 'android/module/src/main/java/io/github/yuejie/connect').glob('*File*.java'))
inputs = sources + fixtures + [Path(__file__)]
ledger = dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
              scope='Host production hooks/client/engine with isolated call fixtures; no APK/device claim',
              commands=records, sources=[dict(path=p.relative_to(root).as_posix(), sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in inputs],
              dependencies=[dict(path=str(p), sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in [android, xposed, junit, hamcrest]],
              apk=None, device_operations=False)
(out / 'ledger.json').write_text(json.dumps(ledger, ensure_ascii=False, indent=2), encoding='utf-8')
sys.exit(records[-1]['exit_code'])
