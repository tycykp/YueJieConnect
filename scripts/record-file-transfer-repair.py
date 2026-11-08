"""Read-only provenance freeze for FILE-001 repair; does not rerun business checks."""
import datetime
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
out = root / 'build/file-transfer-validation'
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

evidence = []
inputs = {}
for relative in ['ledger-lease-repair.json', 'xiaomi-send-hooks/ledger.json']:
    path = out / relative
    record = json.loads(path.read_text(encoding='utf-8-sig'))
    if 'exit_code' in record:
        assert record['exit_code'] == 0
    else:
        assert len(record['commands']) == 2 and all(c['exit_code'] == 0 for c in record['commands'])
    for source in record['sources']:
        source_path = root / source['path']
        assert sha(source_path) == source['sha256'], source['path']
        inputs[source['path']] = source['sha256']
    evidence.append(dict(path=path.relative_to(root).as_posix(), sha256=sha(path)))
path = out / 'native-xiaomi-send.json'
record = json.loads(path.read_text(encoding='utf-8'))
assert record['script_sha256'] == sha(root / 'scripts/verify-file-transfer-xiaomi-send.py')
for reused in record['reused']:
    assert sha(root / reused['path']) == reused['sha256']
evidence.append(dict(path=path.relative_to(root).as_posix(), sha256=sha(path)))
oem = root / 'captures/device-interconnect-20261108/coloros17/analysis/jadx/linker/sources'
oem_paths = [
    'io/grpc/oaf/ServiceJobProvider.java',
    'com/oplus/linker/synergy/wisetransfer/fileservice/icdf/FileServiceManager.java',
    'com/oplus/linker/drag/core/factory/DragClientFactoryManager.java',
    'com/oplus/linker/drag/feature/sdk/SdkCommonDragClientFactory.java',
    'com/oplus/linker/drag/feature/pccast/dragin/PcCastDropHandler.java',
]
summary = dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
    thread_id='01a1bbe4-daaf-7d83-a571-a2218ce9ab1f', issue='FILE-001',
    state='Independent source hook and receive fixes verified; common routing and real Oplus receive decision pending',
    tested_methods=dict(receive_lease=15, xiaomi_source_hooks=11),
    evidence=evidence, sources=inputs,
    oplus_read_only_evidence=[dict(path=(oem / p).relative_to(root).as_posix(), sha256=sha(oem / p)) for p in oem_paths],
    script_sha256=sha(Path(__file__)),
    apk=None, device_operations=False, locks_held=[],
    installed_baseline=dict(version_code=19, version_name='0.5.3-business-v6',
        sha256='2bac018151aab2c48c2e76681d6f4ee43034bbc375e2880288264913cff112e7',
        evidence='docs/workstreams/functional-test.md', tested_repair=False))
(out / 'functional-repair-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'PASS: {len(inputs)} exact source hashes and {len(evidence)} successful ledgers; no tests/devices rerun')
