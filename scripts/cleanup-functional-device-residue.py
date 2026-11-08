"""Inventory and remove only named project test artifacts under an exclusive device lease."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/functional-test-20261108/device-cleanup'
DEVICES = ['b59f8656', '3B161Q00YN200000']
EXACT = {
    '/data/local/tmp/qj-camera-metadata.jar',
    '/data/local/tmp/qj-screen-codec-probe.jar',
    '/data/local/tmp/qj-functional-html-probe.jar',
    '/data/local/tmp/qj-synthetic-actions.xml',
    '/data/local/tmp/qj-functional-final.xml',
    '/sdcard/qj-ui.xml',
}
LEGACY_XML = {
    'b59f8656': {
        '/sdcard/qj-ui.xml': 'regular file|30280|1794140204',
        '/data/local/tmp/qj-dialog.xml': 'regular file|6418|1794134642',
        '/data/local/tmp/qj-gateway.xml': 'regular file|6896|1794134306',
        '/data/local/tmp/qj-hma.xml': 'regular file|29572|1794134501',
        '/data/local/tmp/qj-window.xml': 'regular file|1460|1794132742',
    },
    '3B161Q00YN200000': {
        '/sdcard/qj-oplus-after-stop.xml': 'regular file|14494|1794138360',
        '/sdcard/qj-oplus-native-add.xml': 'regular file|16363|1794138167',
        '/sdcard/qj-oplus-native-connect.xml': 'regular file|8489|1794138186',
        '/sdcard/qj-oplus-native.xml': 'regular file|16449|1794138156',
        '/sdcard/qj-oplus-settings.xml': 'regular file|13661|1794137954',
        '/data/local/tmp/qj-native-ui.xml': 'regular file|19954|1794142524',
        '/data/local/tmp/qj-native.xml': 'regular file|14834|1794135999',
        '/data/local/tmp/qj-window.xml': 'regular file|13189|1794134247',
    },
}
SAFE_DIRS = {'/data/local/tmp', '/sdcard', '/sdcard/Download'}


def adb(serial, shell):
    r = subprocess.run(['adb', '-s', serial, 'shell', shell], capture_output=True, timeout=25)
    if r.returncode:
        raise RuntimeError('ADB operation failed; command output suppressed')
    return r.stdout.decode('utf-8', 'replace')


def require_lock():
    lock = ROOT / 'build/coordination-locks/devices.lock'
    if not lock.exists() or 'functional-cleanup 01a1bbcf-5271-7900-bb0d-bee38aff77b9' not in lock.read_text(encoding='utf-8-sig'):
        raise RuntimeError('Exclusive cleanup device lease required')


def inventory():
    report = {'utc': datetime.now(timezone.utc).isoformat(), 'devices': {}}
    for serial in DEVICES:
        result = report['devices'][serial] = {'files': [], 'test_processes': []}
        ps = adb(serial, 'ps -A -o PID,ARGS')
        for line in ps.splitlines():
            if any(name in line for name in ['QjHtmlClipboard', 'ScreenCastCodecProbe', 'CameraShareCapabilityProbe']):
                result['test_processes'].append(line.strip())
        for directory in SAFE_DIRS:
            names = adb(serial, 'ls -1 ' + shlex.quote(directory)).splitlines()
            for name in names:
                if not re.fullmatch(r'[A-Za-z0-9_.-]+', name) or not re.match(r'(?i)(qj[-_.]|qiangjian[-_.])', name):
                    continue
                path = directory + '/' + name
                metadata = adb(serial, 'stat -c ' + shlex.quote('%F|%s|%Y') + ' ' + shlex.quote(path)).strip()
                result['files'].append({'path': path, 'metadata': metadata, 'known_from_project_script': path in EXACT,
                                        'referenced_by_test_process': any(path in line for line in result['test_processes'])})
        report_path = OUT / 'inventory.json'
        OUT.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    return report


def clean():
    report = inventory()
    for serial, result in report['devices'].items():
        result['removed'] = []
        result['preserved'] = []
        for entry in result['files']:
            path = entry['path']
            legacy = LEGACY_XML.get(serial, {}).get(path)
            authorized = path in EXACT or legacy is not None
            changed_since_inventory = legacy is not None and entry['metadata'] != legacy
            if not authorized or changed_since_inventory or entry['referenced_by_test_process'] or not entry['metadata'].startswith('regular file|'):
                result['preserved'].append(entry)
                continue
            adb(serial, 'rm -f -- ' + shlex.quote(path))
            exists = adb(serial, 'if [ -e ' + shlex.quote(path) + ' ]; then echo PRESENT; else echo ABSENT; fi').strip()
            if exists != 'ABSENT':
                raise RuntimeError('Named test artifact remains after removal')
            result['removed'].append(path)
        result['removed_bytes'] = sum(int(e['metadata'].split('|')[1]) for e in result['files'] if e['path'] in result['removed'])
        # Existing baseline settings were restored before repair dispatch. Do not revoke a
        # newer repair session's permissions, clear app data, or uninstall the actual module.
        result['formal_module_preserved'] = True
        result['new_repair_sessions_and_permissions_preserved'] = True
    (OUT / 'cleanup.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['inventory', 'clean'])
    args = parser.parse_args()
    require_lock()
    value = inventory() if args.mode == 'inventory' else clean()
    print(json.dumps(value, ensure_ascii=False, indent=2))
