"""OnePlus-only debug clipboard sink test; never sends clipboard contents across devices."""
import argparse
import datetime
import json
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERIAL = '3B161Q00YN200000'
parser = argparse.ArgumentParser()
parser.add_argument('--local-sink', action='store_true', required=True,
                    help='Write/read a temporary native test marker and restore the existing clipboard')
parser.parse_args()

def adb(*args):
    return subprocess.check_output(['adb', '-s', SERIAL, *args], timeout=15).decode('utf-8', 'replace')

assert adb('shell', 'getprop', 'ro.product.model').strip() == 'PLQ110', 'Wrong device'

def logs():
    return adb('shell', 'logcat', '-d', '-v', 'threadtime', '-s', 'YueJieOplusTest:I', '*:S').splitlines()

before = set(logs())
adb('shell', 'am', 'broadcast', '-a', 'io.github.yuejie.connect.OPLUS_CLIPBOARD_PROBE', '-p', 'com.oplus.linker')
deadline = time.monotonic() + 8
found = []
while time.monotonic() < deadline:
    found = [line for line in logs() if line not in before and 'clipboard_self_test ' in line]
    if found:
        break
    time.sleep(.4)
success = len(found) == 1 and found[0].endswith('clipboard_self_test native_write_readback_ok restored')
result = dict(time=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),
              model='PLQ110', local_sink_verified=success, logs=found,
              cross_device_verified=False,
              notes='Focused native Activity is required for a reversible clipboard snapshot. '
                    'The debug receiver is DUMP protected. No clipboard content is exported in the result.')
path = ROOT / 'build/oplus-validation/clipboard-round2/local-sink-result.json'
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=True, indent=2))
raise SystemExit(0 if success else 1)
