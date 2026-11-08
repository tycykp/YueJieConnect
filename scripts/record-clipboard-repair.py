"""Read-only input/evidence audit. Never reruns a test or touches a device."""
import ast
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/clipboard-validation'
FILES = ['android/module/src/main/java/io/github/qiangjian/connect/' + name for name in
         ['ClipboardBridge.java', 'ClipboardUriReceiver.java', 'ClipboardUriRoutes.java', 'ClipboardUriCodec.java']]
FILES += ['android/module/src/debug/java/io/github/qiangjian/connect/ClipboardNativeProbe.java']
FILES += ['scripts/' + name for name in ['test-clipboard-native.ps1', 'validate-clipboard-native.py',
    'validate-clipboard-probe.py', 'validate-clipboard-html-probe.py', 'validate-clipboard-uri-routes.py', 'record-clipboard-repair.py']]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    for path in FILES:
        if path.endswith('.py'):
            ast.parse((ROOT / path).read_text(encoding='utf-8'))
    checks = {}
    for suffix in ['html-probe-host/evidence.json', 'probe-host/evidence.json', 'rich-bridge-host/evidence.json',
                   'uri-routes-host/evidence.json', 'compile-validation.json']:
        data = json.loads((OUT / suffix).read_text(encoding='utf-8-sig'))
        mismatches = [p for p, sha in data['source_sha256'].items() if digest(ROOT / p) != sha]
        checks[suffix] = dict(sha256=digest(OUT / suffix), source_mismatches=mismatches, command=data['command'],
                             result=data.get('result', 'compiled'), source_races=data.get('source_races', []))
    valid = all(not check['source_mismatches'] and not check['source_races']
                and check['result'] in {'passed', 'compiled'} for check in checks.values())
    ledger = dict(command='python scripts/record-clipboard-repair.py', timestamp_utc=datetime.now(timezone.utc).isoformat(),
        thread_id='01a1bbe4-d754-7773-85dd-5cd0f1ce029f', result='ready' if valid else 'input_changed',
        scope='CLIP-001 native HTML debug probe and CLIP-002 independent URI routes/strict ACK; no device acceptance',
        source_sha256={p: digest(ROOT / p) for p in FILES}, checks=checks, apk=None, device_operations=False,
        baseline_apk=dict(version_code=19, version_name='0.5.3-business-v6',
            sha256='2bac018151aab2c48c2e76681d6f4ee43034bbc375e2880288264913cff112e7'),
        reused_plain_device_evidence='build/clipboard-validation/device-0eedc3feba4d4fd0a226f4ed799e7c68/result.json',
        locks_held=[])
    (OUT / 'repair-ready-ledger.json').write_text(json.dumps(ledger, ensure_ascii=False, indent=2), encoding='utf-8')
    if not valid:
        raise SystemExit('Input changed: inspect repair-ready-ledger.json; no test or device operation repeated')
    print(f'PASS {len(FILES)} changed input hashes and 5 exact evidence ledgers; no device operation')


if __name__ == '__main__':
    main()
