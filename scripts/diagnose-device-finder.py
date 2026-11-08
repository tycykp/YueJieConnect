"""Read-only FINDER-001 package/process/module diagnostics; never starts playback or sessions."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/device-finder-repair'
OUT.mkdir(parents=True, exist_ok=True)
LOCK = ROOT / 'build/coordination-locks/devices.lock'
LOCK.parent.mkdir(parents=True, exist_ok=True)
lease = dict(task='device-finder-diagnosis', threadId='01a1bbe4-f070-7731-9b98-3abbf216410c',
             utc=datetime.datetime.now(datetime.timezone.utc).isoformat(), lease=uuid.uuid4().hex)
record = dict(command='python scripts/diagnose-device-finder.py', utc=lease['utc'],
              script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              scope='Read-only; no host launch, force-stop, install, pair, request or playback')
continuing = '--continue' in sys.argv
if continuing:
    record = json.loads((OUT / 'readiness-diagnosis.json').read_text(encoding='utf-8'))
    record['continuation'] = dict(command='python scripts/diagnose-device-finder.py --continue',
                                utc=lease['utc'], script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())


def adb(*args, absent_ok=False):
    result = subprocess.run(['C:/adb/adb.exe', '-s', '3B161Q00YN200000', *args],
                            capture_output=True, encoding='utf-8', errors='replace', timeout=20)
    record.setdefault('commands', []).append(dict(args=args, exit_code=result.returncode))
    if result.returncode and not (absent_ok and result.returncode == 1 and not result.stdout.strip()):
        raise RuntimeError(result.stderr.strip())
    return result.stdout


owned = False
try:
    # os.O_EXCL on Windows uses an exclusive CreateNew operation, never overwrites a lease.
    fd = os.open(LOCK, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    owned = True
    with os.fdopen(fd, 'w', encoding='utf-8') as handle:
        json.dump(lease, handle)
    if not continuing:
        record['device_state'] = adb('get-state').strip()
        package = adb('shell', 'dumpsys package com.coloros.findmyphone')
        record['package'] = [line.strip() for line in package.splitlines() if re.match(
            r'\s*(userId=|appId=|versionCode=|versionName=|pkg=Package\{|targetSdk=|sharedUser|User 0:)', line)]
        uid = re.search(r'\b(?:userId|appId)=(\d+)', package)
        if uid:
            record['uid_packages'] = adb('shell', f'cmd package list packages --uid {uid.group(1)}').splitlines()
        paths = adb('shell', 'pm path com.coloros.findmyphone').splitlines()
        record['apk_paths'] = paths
        record['apk_sha256'] = [adb('shell', 'sha256sum ' + p.removeprefix('package:')).strip() for p in paths]
    record['process'] = adb('shell', 'pidof com.coloros.findmyphone', absent_ok=True).strip()
    logs = adb('logcat', '-d', '-v', 'threadtime', '-s', 'QiangJian:I', 'QiangJianFinder:I', '*:S')
    # Only implementation diagnostics, no raw OEM/account/clipboard/notification objects.
    record['module_logs'] = [line for line in logs.splitlines() if re.search(
        r'finder_|process=com.coloros.findmyphone|unsupported_package_identity|caller_verified|unsupported_hook_signature', line)]
    record['scope_config_files'] = adb('shell', "su -c 'ls /data/adb/lspd/config'").splitlines()
    record['result'] = 'diagnostic-collected'
    record.pop('error', None)
except FileExistsError:
    record['result'] = 'lock-busy-no-device-commands'
except Exception as failure:
    record['result'] = 'diagnostic-incomplete'
    record['error'] = str(failure)
finally:
    if owned and json.loads(LOCK.read_text(encoding='utf-8')).get('lease') == lease['lease']:
        LOCK.unlink()
    (OUT / 'readiness-diagnosis.json').write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({k: v for k, v in record.items() if k not in ('commands',)}, ensure_ascii=False, indent=2))
