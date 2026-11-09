"""Read-only current-source/evidence audit; does not repeat any test, build or device step."""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import re

root = Path(__file__).resolve().parent.parent
out = root / 'build/calls-sms-validation'
def read(relative):
    return json.loads((out / relative).read_text('utf-8-sig'))
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def compiled_map(record):
    return {str(Path(e['path'])).lower(): e['sha256'] for e in record['sources']}
source = root / 'android/module/src/main/java/io/github/yuejie/connect'
test = root / 'android/module/src/test/java/io/github/yuejie/connect'
checks = []
for evidence, pairs, count in [
    ('repair/audio/validation.json', [(source, 'TelephonySyncAudioSession.java'), (test, 'TelephonySyncAudioSessionTest.java')], 14),
    ('relay/validation.json', [(source, 'TelephonySyncController.java'), (source, 'TelephonySyncRelay.java'), (test, 'TelephonySyncRelayTest.java')], 24),
    ('sms-native/validation.json', [(source, 'TelephonySyncSmsNative.java'), (test, 'TelephonySyncSmsNativeTest.java')], 23),
]:
    record = read(evidence); hashes = compiled_map(record)
    log = (out / evidence).parent / 'tests.txt'
    observed = re.search(r'OK \((\d+) tests\)', log.read_text('utf-8-sig'))
    assert observed and int(observed.group(1)) == count, (evidence, 'test count mismatch')
    for directory, name in pairs:
        path = directory / name
        assert hashes[str(path).lower()] == digest(path), (evidence, name, 'test input changed')
    checks.append({'evidence': str(out / evidence), 'tests': count, 'test_log_sha256': digest(log),
                   'actual_command': record['command'].replace('-NoProfile -File', '-NoProfile -ExecutionPolicy Bypass -File'),
                   'inputs_match': [name for _, name in pairs]})

# The final selected SMS suite compiled every owned Android main/test source, without rerunning the other suites.
compile_record = read('sms-native/validation.json')
compiled = compiled_map(compile_record)
for path, expected in compiled.items():
    assert digest(Path(path)) == expected, (path, 'latest owned compilation input changed')
lint = read('repair/lint/validation.json')
assert lint['exitCode'] == 0 and not lint['races']
for path, expected in lint['sources'].items():
    assert digest(Path(path)) == expected, (path, 'lint input changed')
scripts = []
for evidence, script in [
    ('repair/audio-service-capture.json', 'capture-telephony-audio-service.ps1'),
    ('repair/audio-service-inspection.json', 'inspect-telephony-audio-service.py'),
    ('repair/native-audio-endpoints.json', 'verify-telephony-audio-endpoints.py'),
    ('repair/sms-consumer-capture.json', 'capture-telephony-sms-consumer.ps1'),
    ('repair/sms-consumer-inspection.json', 'inspect-telephony-sms-consumer.py'),
    ('repair/sms-import-native-evidence.json', 'verify-telephony-sms-import-path.py'),
]:
    record = read(evidence)
    expected = record.get('script_sha256', record.get('scriptSha256'))
    assert digest(root / 'scripts' / script) == expected, (script, 'evidence script changed')
    scripts.append({'script': script, 'sha256': expected, 'evidence': evidence})
owned = sorted(list(source.glob('TelephonySync*.java')) + list(source.glob('OplusTelephony*.java')) + list(source.glob('XiaomiTelephony*.java')))
result = {'thread': '01a1bbe4-ec32-7d01-a5de-8fc711849d20', 'utc': datetime.now(timezone.utc).isoformat(),
          'command': 'python scripts/audit-telephony-repair.py', 'script_sha256': digest(Path(__file__)),
          'scope': 'Read-only SHA audit; tests/build/install/native audio and SMS were not repeated.',
          'isolated_tests': checks, 'test_total': sum(c['tests'] for c in checks),
          'test_runner_sha256': digest(root / 'scripts/test-telephony.ps1'), 'all_owned_main_and_test_compilation_current': True,
          'lint_current_and_unchanged': True, 'sources': [{'path': str(p), 'sha256': digest(p)} for p in owned],
          'native_evidence_scripts': scripts,
          'module_apk': {'version': 'code19/0.5.3-business-v6', 'sha256': '2bac018151aab2c48c2e76681d6f4ee43034bbc375e2880288264913cff112e7',
                         'scope': 'Existing installed baseline only; this repair produced/installed no accepted APK.'},
          'remaining': ['Authenticated phone/InCallUI process registration and typed call routing',
                        'Fresh native per-call audio approval and separately authenticated AUDIO channel integration',
                        'True cross-device SMS identifier association and Xiaomi native SMS content consumer',
                        'New APK and synthetic native end-to-end verification; no live call/microphone/SMS action was performed']}
(out / 'repair/final-ledger.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'PASS: {len(owned)} owned production sources, 61 isolated tests by exact inputs, final compilation/lint and native evidence match current SHA; no checks repeated')
