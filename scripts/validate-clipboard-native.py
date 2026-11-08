"""Run only through test-clipboard-native.ps1 (owns both-device lock).

Requires an already authenticated, mutually confirmed real session. Never installs, pairs,
confirms, or constructs a native consumer. Logs only the probe's synthetic/sanitized events.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import time
import uuid
from datetime import datetime, timezone, timedelta
from pathlib import Path
from debug_control import command as debug_command
from clipboard_device_guard import check_native_foreground

ROOT = Path(__file__).resolve().parents[1]
DEVICES = [('b59f8656', 'com.xiaomi.mirror', 1), ('3B161Q00YN200000', 'com.oplus.linker', 2)]
ACTION = 'io.github.qiangjian.connect.CLIPBOARD_NATIVE_PROBE'
RESTORED = {'restored', 'unchanged', 'preserved_new_clipboard'}


def runtime_matches(value, revision, package):
    if not isinstance(revision, str) or re.fullmatch('[0-9a-f]{64}', revision) is None or not isinstance(value, dict):
        return False
    rows = value.get('clients')
    if value.get('status') != 'ok' or value.get('expected_revision') != revision or not isinstance(rows, list) or not 1 <= len(rows) <= 64:
        return False
    for row in rows:
        if (not isinstance(row, dict) or row.get('matches') is not True or row.get('revision') != revision
                or type(row.get('uid')) is not int or row['uid'] <= 0
                or type(row.get('pid')) is not int or row['pid'] <= 0
                or type(row.get('age_ms')) is not int or not 0 <= row['age_ms'] < 10_000):
            return False
    return any(row.get('package') == package for row in rows)


def parse_events(output, run):
    events = []
    occurrences = {}
    for line in output.splitlines():
        start = line.find('{')
        if start < 0:
            continue
        try:
            event = json.loads(line[start:])
        except ValueError:
            continue
        if (event.get('run') == run and set(event) == {'run', 'source', 'session', 'stage', 'leg', 'result'}
                and event.get('source') in (1, 2)
                and re.fullmatch('[0-9a-f]{0,64}', event.get('session', ''))
                and re.fullmatch('[a-z_]{1,50}', event.get('stage', ''))
                and re.fullmatch('[a-z_]{1,64}', event.get('result', ''))
                and event.get('leg') in (0, 1, 2)):
            prefix = re.fullmatch(r'\s*([0-9]+\.[0-9]+)\s+([0-9]+)\s+([0-9]+)\s+I\s+QJClipboardProbe\s*:\s*', line[:start])
            if prefix:
                identity = ':'.join(prefix.groups())
                key = (identity, line[start:])
                occurrences[key] = occurrences.get(key, 0) + 1
                event['log_record'] = identity + ':' + str(occurrences[key])
            events.append(event)
    return events


def find(events, stage, leg=0):
    return next((e for e in reversed(events) if e['stage'] == stage and e['leg'] == leg), None)


def assert_direction(source, sink, leg):
    for events, stage, expected in [(source, 'source', 'platform_copy'), (source, 'receipt', 'remote_ack'),
                                    (sink, 'sink', 'native_write_readback'), (sink, 'readback', 'matches')]:
        event = find(events, stage, leg)
        if event is None or event['result'] != expected:
            raise RuntimeError(f'leg {leg} {stage}: {event["result"] if event else "missing"}')
    sessions = {e['session'] for events in [source, sink] for e in events
                if e['leg'] == leg and e['stage'] in {'source', 'receipt', 'sink', 'readback'}}
    if len(sessions) != 1 or not re.fullmatch('[0-9a-f]{64}', next(iter(sessions))):
        raise RuntimeError('direction session mismatch')
    if any(e['stage'] == 'sink' and e['leg'] == leg for e in source):
        raise RuntimeError(f'leg {leg}: clipboard echoed back to its source')
    if len([e for e in sink if e['stage'] == 'sink' and e['leg'] == leg]) != 1:
        raise RuntimeError(f'leg {leg}: duplicate sink completion; inspect native/replay handling')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--lock-owner', required=True)
    parser.add_argument('--direction', choices=['both', 'xiaomi-to-oplus', 'oplus-to-xiaomi'], default='both')
    parser.add_argument('--format', choices=['plain', 'html'], default='plain')
    parser.add_argument('--build-ledger', type=Path, default=ROOT / 'build/clipboard-validation/integrated-ledger.json')
    args = parser.parse_args()
    lock = ROOT / 'build/coordination-locks/devices.lock'
    if not lock.exists() or f'clipboard {args.lock_owner} ' not in lock.read_text(encoding='utf-8-sig'):
        raise SystemExit('Run with the owning PowerShell lock wrapper')
    adb = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platform-tools/adb.exe'
    run = uuid.uuid4().hex
    out = ROOT / 'build/clipboard-validation' / ('device-' + run)
    out.mkdir(parents=True, exist_ok=False)
    evidence = dict(run=run, timestamp=datetime.now(timezone(timedelta(hours=8))).isoformat(),
                    apk_sha256=hashlib.sha256(args.apk.read_bytes()).hexdigest(), devices={},
                    result='incomplete', native_source='platform clipboard event; OEM dispatch required',
                    direction=args.direction, format=args.format,
                    scope='synthetic ' + args.format + ' only; platform copy/OEM readback/real ACK, not native connection or URI acceptance')
    ledger = json.loads(args.build_ledger.read_text(encoding='utf-8-sig'))
    if ledger.get('apk_sha256') != evidence['apk_sha256']:
        raise SystemExit('Build/source ledger must match the installed artifact under test')
    evidence['source_sha256'] = ledger['source_sha256']
    revision = ledger.get('runtime_input_sha')
    if args.format == 'html' and not (isinstance(revision, str) and re.fullmatch('[0-9a-f]{64}', revision)):
        raise SystemExit('HTML requires an exact build ledger with runtime_input_sha')
    if revision is not None:
        if not isinstance(revision, str) or not re.fullmatch('[0-9a-f]{64}', revision):
            raise SystemExit('Invalid runtime input revision in build ledger')
        evidence['runtime_input_sha'] = revision
    scripts = [ROOT / 'scripts/test-clipboard-native.ps1', Path(__file__)]
    evidence['validation_script_sha256'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in scripts}
    attempted = []
    traces = {}

    def command(serial, *argv):
        result = subprocess.run([str(adb), '-s', serial, *argv], capture_output=True, timeout=25)
        if result.returncode:
            # Do not persist arbitrary OEM stderr/logs that may contain private data.
            raise RuntimeError('adb command failed: ' + argv[0])
        return result.stdout.decode('utf-8', 'replace')

    def send(device, op, leg=0):
        serial, package, _ = device
        if args.format == 'html' and op in {'prepare', 'copy', 'finish'}:
            check_native_foreground(serial)
        command(serial, 'shell', 'am', 'broadcast', '-a', ACTION, '-p', package,
                '--es', 'run', run, '--es', 'op', op, '--ei', 'leg', str(leg), '--es', 'format', args.format)

    def read(device):
        serial, _, _ = device
        collected = traces.setdefault(serial, [])
        for event in parse_events(command(serial, 'logcat', '-d', '-v', 'epoch', '-s', 'QJClipboardProbe:I', '*:S'), run):
            if event not in collected:
                collected.append(event)
        return collected

    def verify_runtime():
        if revision is None:
            return
        snapshots = {}
        for serial, package, _ in DEVICES:
            value = debug_command(serial, {'op': 'bridge_runtime_status'})
            if not runtime_matches(value, revision, package):
                raise RuntimeError('native runtime not current: ' + serial)
            snapshots[serial] = value
        evidence.setdefault('runtime_checks', []).append(snapshots)

    def wait(device, stage, leg=0, seconds=20, terminal=None):
        deadline = time.monotonic() + seconds
        while True:
            event = find(read(device), stage, leg)
            if event is not None and (terminal is None or event['result'] in terminal):
                return event
            if time.monotonic() >= deadline:
                raise RuntimeError(stage + ' event timeout')
            time.sleep(0.4)

    try:
        # Verify both installed artifacts before any clipboard mutation.
        for device in DEVICES:
            serial, _, _ = device
            paths = command(serial, 'shell', 'pm', 'path', 'io.github.qiangjian.connect').strip().splitlines()
            if len(paths) != 1 or not re.fullmatch(r'package:/data/app/[A-Za-z0-9_=+./~-]+\.apk', paths[0]):
                raise RuntimeError('unexpected installed module path')
            installed = command(serial, 'shell', 'sha256sum', paths[0][8:]).split()[0]
            if installed != evidence['apk_sha256']:
                raise RuntimeError('installed APK differs from supplied artifact')
            package = command(serial, 'shell', 'dumpsys', 'package', 'io.github.qiangjian.connect')
            version = re.search(r'versionCode=(\d+)', package)
            name = re.search(r'versionName=([^\s]+)', package)
            evidence['devices'][serial] = dict(apk_sha256=installed, version_code=version.group(1) if version else None,
                                               version_name=name.group(1) if name else None)
        verify_runtime()
        sessions = []
        for device in DEVICES:
            attempted.append(device)
            send(device, 'prepare')
            event = wait(device, 'prepare')
            if event['result'] != ('armed_html' if args.format == 'html' else 'armed'):
                raise RuntimeError('prepare: ' + event['result'])
            sessions.append(event['session'])
        if len(set(sessions)) != 1 or not re.fullmatch('[0-9a-f]{64}', sessions[0]):
            raise RuntimeError('both devices must have the same real authorized session')
        for leg, source, sink in [(1, DEVICES[0], DEVICES[1]), (2, DEVICES[1], DEVICES[0])]:
            if args.direction == 'xiaomi-to-oplus' and leg != 1 or args.direction == 'oplus-to-xiaomi' and leg != 2:
                continue
            send(source, 'copy', leg)
            event = wait(source, 'source', leg)
            if event['result'] != 'platform_copy':
                raise RuntimeError('source: ' + event['result'])
            wait(source, 'receipt', leg)
            send(sink, 'check', leg)
            wait(sink, 'readback', leg)
            # Observe the native loop-suppression window; reading logs does not generate copies.
            for _ in range(4):
                read(source)
                read(sink)
                time.sleep(0.3)
            assert_direction(read(source), read(sink), leg)
        verify_runtime()
        evidence['result'] = 'passed'
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        evidence['result'] = 'failed'
        evidence['failure'] = str(error) if isinstance(error, RuntimeError) else type(error).__name__
    finally:
        # Restoration is attempted for both participants even when preparation or transfer failed.
        for device in attempted:
            try:
                send(device, 'finish')
                prepared = find(read(device), 'prepare')
                if prepared and prepared['result'] in {'armed', 'armed_html'}:
                    restored = wait(device, 'restore', seconds=15, terminal=RESTORED)
                    if restored['result'] not in RESTORED:
                        raise RuntimeError('restore: ' + restored['result'])
            except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
                evidence['result'] = 'failed'
                evidence.setdefault('cleanup_errors', []).append(dict(serial=device[0], error=type(error).__name__))
        evidence['events'] = traces
        (out / 'result.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    print(evidence['result'].upper() + ': ' + str(out / 'result.json'))
    return 0 if evidence['result'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
