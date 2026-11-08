"""OnePlus-only bounded validation. Never confirms pairing or operates the Xiaomi device."""
import argparse
import hashlib
import json
import subprocess
import time
from pathlib import Path
from debug_control import command

SERIAL = '3B161Q00YN200000'
ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--probe', action='store_true', help='Invoke the installed debug-only native DeviceKit query')
args = parser.parse_args()

def adb(*arguments):
    return subprocess.check_output(['adb', '-s', SERIAL, *arguments], timeout=20).decode('utf-8', 'replace')

assert adb('shell', 'getprop', 'ro.product.model').strip() == 'PLQ110'
status = command(SERIAL, {'op': 'bridge_status'})['bridge']
summary = {k: status[k] for k in ('phase', 'last_error', 'socket_errno', 'local_confirmed', 'remote_confirmed',
                                'requests_sent', 'requests_received', 'responses_received')}
summary['projection_present'] = status.get('projection') is not None
summary['probe_invoked'] = False
if args.probe:
    if status['phase'] != 'active' or not summary['projection_present']:
        raise SystemExit('No active, bilaterally confirmed real peer; refusing native probe.')
    before = set(adb('shell', 'logcat', '-d', '-v', 'threadtime', '-s', 'QiangJianOplusTest:I', '*:S').splitlines())
    adb('shell', 'am', 'broadcast', '-a', 'io.github.qiangjian.connect.OPLUS_PROFILE_PROBE', '-p', 'com.oplus.linker')
    summary['probe_invoked'] = True
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        current = adb('shell', 'logcat', '-d', '-v', 'threadtime', '-s', 'QiangJianOplusTest:I', '*:S').splitlines()
        new = [line for line in current if line not in before]
        if any('native_battery_received' in line or 'native_query_error' in line or 'probe_error' in line for line in new):
            summary['this_probe_log'] = new
            break
        time.sleep(.5)
    else:
        summary['this_probe_log'] = new
        summary['probe_timed_out'] = True
summary['probe_log'] = adb('shell', 'logcat', '-d', '-v', 'threadtime', '-s', 'QiangJianOplusTest:I', '*:S').splitlines()[-20:]
summary['scope'] = 'Native API probe is diagnostic stimulus, not proof of a normal UI click or full native connection.'
apk = ROOT / 'build/oplus-validation/module-oplus.apk'
if apk.exists():
    summary['installed_candidate_sha256'] = hashlib.sha256(apk.read_bytes()).hexdigest()
out = ROOT / 'build/oplus-validation/runtime.json'
out.parent.mkdir(exist_ok=True, parents=True)
out.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(summary, ensure_ascii=True, indent=2))
