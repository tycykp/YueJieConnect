"""Record the latest source compile plus compatible incremental app-relay tests; never run another task's checks."""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/app-relay-validation'
BASE = 'android/module/src/main/java/io/github/yuejie/connect/'
TEST = 'android/module/src/test/java/io/github/yuejie/connect/'

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

stages = [
    ('round2-tests-ledger.json', 32, [BASE + name + '.java' for name in (
        'AppRelayRequest', 'AppRelayTasks', 'AppRelayCatalog', 'AppRelayCatalogCodec', 'AppRelayDirectory', 'AppRelayPeer')]
        + [TEST + name + 'Test.java' for name in ('AppRelayRequest', 'AppRelayTasks', 'AppRelayDirectory')]),
    ('receipts-tests-ledger.json', 7, [BASE + name + '.java' for name in ('AppRelayCodec', 'AppRelaySender')]
        + [TEST + name + 'Test.java' for name in ('AppRelayCodec', 'AppRelaySender')]),
    ('payload-tests-ledger.json', 4, [BASE + 'OplusRelayPayload.java', TEST + 'OplusRelayPayloadTest.java']),
    ('receive-tests-ledger.json', 4, [BASE + name + '.java' for name in ('AppRelayReceiveTokens', 'FileTransferReceiveLease', 'FileTransferEngine')]
        + [TEST + 'AppRelayReceiveTokensTest.java']),
]
checks = []
for ledger_name, count, paths in stages:
    ledger = json.loads((OUT / ledger_name).read_text(encoding='utf-8-sig'))
    assert ledger['result'] == 'passed', ledger_name
    sources = {value['path'].replace('\\', '/'): value['sha256'] for value in ledger['sources']}
    for path in paths:
        digest = sha(ROOT / path)
        assert sources[path] == digest, f'{ledger_name}: tested source changed: {path}'
        checks.append(dict(ledger=ledger_name, path=path, sha256=digest))

compiled = json.loads((OUT / 'integration-compile-ledger.json').read_text(encoding='utf-8-sig'))
for source in compiled['sources']:
    assert source['sha256'] == sha(ROOT / source['path']), f"Compile source changed: {source['path']}"
dex = json.loads((OUT / 'dex-round2-evidence.json').read_text(encoding='utf-8'))
assert len(dex) == 11
summary = dict(utc=datetime.now(timezone.utc).isoformat(), result='passed',
    thread='01a1bba3-090f-7d32-8558-d0b955139490',
    note='32 related tests first passed; changed receipt subset 7, changed payload subset 4, receive subset 4 passed subsequently. 37 distinct test methods across affected classes. Native Android directories/adapter/bridge compile only.',
    compatible_test_sources=checks, compiled_sources=compiled['sources'],
    dex_classes=len(dex), dex_command='python scripts/verify-app-relay-samples.py --round2-only',
    evidence_sha256={name:sha(OUT / name) for name in (
        'round2-tests-ledger.json','receipts-tests-ledger.json','payload-tests-ledger.json','receive-tests-ledger.json',
        'integration-compile-ledger.json','tests-round2.log','tests-receipts.log','tests-payload.log','tests-receive.log','dex-round2-evidence.json','dex-round2.log')},
    verifier_sha256=sha(ROOT / 'scripts/verify-app-relay-samples.py'),
    recorder_sha256=sha(Path(__file__)), apk=None, device_actions=[], locks_acquired=[])
(OUT / 'round2-validation-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS: compatible incremental test source hashes, final full relay compilation, 11 original DEX classes; no APK/device claims.')
