"""Audit exact compatible repair inputs without rerunning tests, Gradle, or device actions."""
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/app-relay-validation'
MAIN = 'android/module/src/main/java/io/github/yuejie/connect/'
TEST = 'android/module/src/test/java/io/github/yuejie/connect/'

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def read(name):
    return json.loads((OUT/name).read_text(encoding='utf-8-sig'))

continuation = '--continuation' in sys.argv
compatible = []
scopes = [
    ('sync-tests-ledger.json', 10, 'tests-sync.log',
     [MAIN+name+'.java' for name in ('AppRelaySync','AppRelayCodec','AppRelayRequest','AppRelayCatalog','AppRelayTasks','XiaomiRelayCatalog','OplusRelayPayload')]
     +[TEST+'AppRelaySyncTest.java']),
    ('repair-tests-ledger.json', 3, 'tests-repair.log',
     [MAIN+name+'.java' for name in ('OplusRelayControl','OplusRelayPayload','AppRelayRequest','AppRelayCatalog')]
     +[TEST+'OplusRelayControlTest.java']),
]
if continuation:
    scopes[0] = ('continuation-tests-ledger.json', 30, 'tests-continuation.log',
        [MAIN+name+'.java' for name in ('AppRelaySync','AppRelaySyncClient','AppRelayDocumentDecisions','AppRelayDocumentNegotiation',
                                      'AppRelayCodec','AppRelayRequest','AppRelayCatalog','AppRelayTasks','XiaomiRelayCatalog',
                                      'OplusRelayPayload','FileTransferNegotiation','FileTransferEngine')]
        +[TEST+name+'Test.java' for name in ('AppRelaySync','AppRelaySyncClient','AppRelayDocumentDecisions','AppRelayCodec')])
for ledger_name, reused_count, log, paths in scopes:
    ledger = read(ledger_name)
    assert ledger['result'] == 'passed'
    sources = {v['path'].replace('\\','/'):v['sha256'] for v in ledger['sources']}
    for path in paths:
        assert sources[path] == sha(ROOT/path), f'{ledger_name}: changed tested source {path}'
        compatible.append(dict(ledger=ledger_name,path=path,sha256=sources[path]))
    # Every test uses the recorded Android implementation of org.json, not a JSON stub.
    for path, digest in sources.items():
        if '/json-src/org/json/' in path:
            assert digest == sha(ROOT/path), path
    log_bytes = (OUT/log).read_bytes()
    assert 'OK (' in log_bytes.decode('utf-16' if log_bytes.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig')

compiled = read('integration-compile-ledger.json')
assert compiled['result'] == 'passed' and not compiled['source_races']
before = {v['path']:v['sha256'] for v in compiled['before_sources']}
for source in compiled['sources']:
    assert source['sha256'] == before[source['path']] == sha(ROOT/source['path']), source['path']
dex = read('dex-repair-evidence.json')
assert dex['result'] == 'passed' and len(dex['classes']) == 14
assert dex['script_sha256'] == sha(ROOT/'scripts/verify-app-relay-repair.py')
if continuation:
    databus = read('databus-native-evidence.json')
    assert databus['result'] == 'passed' and len(databus['classes']) == 8
    assert databus['script_sha256'] == sha(ROOT/'scripts/verify-app-relay-databus.py')

summary = dict(utc=datetime.now(timezone.utc).isoformat(),result='passed',
    thread='01a1bbe4-e4fe-7eb3-9b4b-b694a6e5a4b6',
    commands=[read(name)['command'] for name in ('repair-tests-ledger.json','continuation-tests-ledger.json' if continuation else 'sync-tests-ledger.json','integration-compile-ledger.json')]
             +['python scripts/verify-app-relay-repair.py','python scripts/record-app-relay-repair.py'+(' --continuation' if continuation else '')],
    tests='33 distinct compatible methods: latest continuation 30 plus unchanged control 3 reused. No unrelated business or old device baseline rerun.' if continuation
        else '13 distinct methods: latest sync 10; unchanged control 3 reused from initial repair 12. No unrelated business or old baseline tests rerun.',
    compatible_test_sources=compatible,compiled_sources=compiled['sources'],source_races=[],
    native_reflection='14 pinned original DEX classes; signatures only, not runtime execution',
    evidence_sha256={name:sha(OUT/name) for name in ('repair-tests-ledger.json','continuation-tests-ledger.json' if continuation else 'sync-tests-ledger.json','integration-compile-ledger.json',
        'tests-repair.log','tests-continuation.log' if continuation else 'tests-sync.log','compile-integration.log','dex-repair-evidence.json')},
    recorder_sha256=sha(Path(__file__)),baseline_apk=dict(version='code19/0.5.3-business-v6',
        sha256='2bac018151aab2c48c2e76681d6f4ee43034bbc375e2880288264913cff112e7',scope='inherited functional-test only; repair sources are not installed'),
    repair_apk=None,device_actions=[],locks_acquired=[],
    remaining=['actual selected DATA logical peer and slot owner',
               'integrated file receiver attachment and native document read grant','integrated APK and real native roundtrip/cancel validation'])
if continuation:
    summary['native_reflection'] = '14 native sync classes plus 8 DataBus classes; pinned exact signatures, no runtime-readiness inference'
    summary['evidence_sha256']['databus-native-evidence.json'] = sha(OUT/'databus-native-evidence.json')
(OUT/('continuation-validation-summary.json' if continuation else 'repair-validation-summary.json')).write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS: '+('33 continuation' if continuation else '13 repair')+' compatible tests, exact-source compile without races, pinned native DEX evidence. No APK or device claim.')
