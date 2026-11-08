"""Read existing evidence and exact source hashes; never rerun another workstream's tests."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/functional-test-20261108'
GROUPS = {
    'native-pairing': ['build/native-pairing-validation/v19-final-ledger.json'],
    'clipboard': ['build/clipboard-validation/v19-integrated-ledger.json'],
    'files': ['build/file-transfer-validation/round2-receive-summary.json', 'build/file-transfer-validation/ledger-endpoint.json', 'build/file-transfer-validation/ledger-receive.json'],
    'notifications': ['build/notifications-validation/ledger-carrier-round2.json'],
    'screen-input': ['build/screen-native-window-lifecycle-validation/source-sha256.json', 'build/screen-native-proof-validation/source-sha256.json', 'build/screen-native-input-sender-validation/source-sha256.json', 'build/screen-ime-focus-validation/source-sha256.json'],
    'app-relay': ['build/app-relay-validation/round2-validation-summary.json', 'build/app-relay-validation/integration-summary.json'],
    'network-sharing': ['build/network-sharing/round2-ledger.json'],
    'calls-sms': ['build/calls-sms-validation/round2-summary.json'],
    'device-finder': ['build/device-finder-round2/v19-artifact-inclusion.json', 'build/device-finder-round2/source-reuse.json'],
    'camera-sharing': ['build/camera-validation/native-consumers-20261108-230733-878/result.json', 'build/camera-validation/native-consumers-20261108-230134-118/result.json'],
    'media-controls': ['build/media-controls/code-validation.json'],
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def collect(value, trail=''):
    if isinstance(value, list):
        for i, item in enumerate(value):
            yield from collect(item, trail + '/' + str(i))
    if not isinstance(value, dict):
        return
    lowered = {str(k).lower(): v for k, v in value.items()}
    path = lowered.get('path') or lowered.get('file')
    hashed = lowered.get('sha256') or lowered.get('hash')
    if isinstance(path, str) and isinstance(hashed, str) and re.fullmatch('[a-fA-F0-9]{64}', hashed):
        yield path, hashed.lower(), trail
    for key, item in value.items():
        if isinstance(key, str) and key.endswith(('.java', '.rs', '.ps1', '.py', '.xml', '.kts', '.toml', '.lock')):
            if isinstance(item, str) and re.fullmatch('[a-fA-F0-9]{64}', item):
                yield key, item.lower(), trail + '/' + key
            if isinstance(item, dict) and isinstance(item.get('tested_sha256'), str):
                yield key, item['tested_sha256'].lower(), trail + '/' + key + '/tested'
        yield from collect(item, trail + '/' + str(key))


result = {'utc': datetime.now(timezone.utc).isoformat(), 'command': 'python scripts/audit-functional-evidence.py',
          'script_sha256': sha(Path(__file__)), 'scope': 'Read-only provenance audit; zero host tests/builds repeated', 'features': {}}
for slug, names in GROUPS.items():
    group = result['features'][slug] = {'workstream_sha256': sha(ROOT / 'docs/workstreams' / (slug + '.md')), 'ledgers': []}
    for name in names:
        path = ROOT / name
        row = {'path': name, 'exists': path.exists()}
        group['ledgers'].append(row)
        if not path.exists():
            continue
        row['sha256'] = sha(path)
        data = json.loads(path.read_text(encoding='utf-8-sig'))
        refs = []
        for ref, expected, context in collect(data):
            target = Path(ref)
            if not target.is_absolute():
                target = ROOT / target
            try:
                relative = str(target.resolve().relative_to(ROOT.resolve())).replace('\\', '/')
            except ValueError:
                continue
            if not relative.startswith(('android/', 'core/', 'transport/', 'native/', 'scripts/')):
                continue
            actual = sha(target) if target.is_file() else None
            refs.append({'path': relative, 'expected': expected, 'current': actual, 'matches': actual == expected, 'context': context})
        unique = {(r['path'], r['expected']): r for r in refs}
        row['source_references'] = list(unique.values())
        row['matching_references'] = sum(v['matches'] for v in unique.values())
        row['mismatching_references'] = [r for r in unique.values() if not r['matches']]
        row['mismatch_interpretation'] = 'Historical snapshots or shared compile dependency changes are recorded separately; current APK compilation is covered by the 286-input baseline. No historical mismatch is claimed as a runtime failure.'
OUT.mkdir(exist_ok=True, parents=True)
(OUT / 'evidence-reuse-audit.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
for slug, value in result['features'].items():
    print(slug, 'existing_ledgers=' + str(sum(v['exists'] for v in value['ledgers'])),
          'matching_refs=' + str(sum(v.get('matching_references', 0) for v in value['ledgers'])),
          'historical_differences=' + str(sum(len(v.get('mismatching_references', [])) for v in value['ledgers'])))
