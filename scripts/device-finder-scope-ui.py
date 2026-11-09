"""Exclusive, observed Android manager UI step for this module's Finder scope."""
import argparse
import datetime
import io
import json
import os
from pathlib import Path
import subprocess
import uuid
import xml.etree.ElementTree as ET
from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/device-finder-repair'
LOCK = ROOT / 'build/coordination-locks/devices.lock'
parser = argparse.ArgumentParser()
parser.add_argument('step')
parser.add_argument('--launch', action='store_true')
parser.add_argument('--tap', nargs=2)
parser.add_argument('--text')
parser.add_argument('--expect', help='Existing XML text or content-desc required before mutation')
parser.add_argument('--reference', help='Reviewed manager detail screenshot; require identical app content before tap')
parser.add_argument('--enable-finder', action='store_true', help='One checkbox change through reviewed native manager pages')
args = parser.parse_args()
lease = dict(task='device-finder-scope-ui', threadId='01a1bbe4-f070-7731-9b98-3abbf216410c',
             lease=uuid.uuid4().hex, utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
record = dict(lease=lease, commands=[])
def adb(*command):
    result = subprocess.run(['C:/adb/adb.exe', '-s', '3B161Q00YN200000', *command], capture_output=True, timeout=25)
    record['commands'].append(dict(args=command, exit_code=result.returncode))
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', 'replace'))
    return result.stdout
def dump():
    adb('shell', 'uiautomator dump /data/local/tmp/yj-finder-scope.xml')
    return adb('exec-out', 'cat /data/local/tmp/yj-finder-scope.xml').decode('utf-8', 'replace')
def matches(reference_path):
    reference = Image.open(reference_path).convert('RGB')
    current = Image.open(io.BytesIO(adb('exec-out', 'screencap -p'))).convert('RGB')
    region = (30, 320, 1230, 1850)
    return current.size == reference.size and not ImageChops.difference(current.crop(region), reference.crop(region)).getbbox()
owned = False
try:
    fd = os.open(LOCK, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    owned = True
    with os.fdopen(fd, 'w', encoding='utf-8') as stream:
        json.dump(lease, stream)
    if args.launch:
        component = adb('shell', 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER org.lsposed.manager').decode().strip().splitlines()[-1]
        if not component.startswith('org.lsposed.manager/'):
            raise RuntimeError('manager_launcher_not_resolved')
        adb('shell', 'am start -W -n ' + component)
    if args.enable_finder:
        dump()
        if not matches(OUT / 'scope-current.png'):
            raise RuntimeError('reviewed_module_list_changed')
        adb('shell', 'input tap 580 2530')
        dump()
        if not matches(OUT / 'scope-module-detail.png'):
            raise RuntimeError('reviewed_module_scope_changed')
        adb('shell', 'input tap 1128 1398')
    elif args.tap or args.text:
        before = dump()
        tree = ET.fromstring(before)
        if not any(node.get('package') == 'org.lsposed.manager' for node in tree.iter('node')):
            raise RuntimeError('manager_not_visible')
        if args.reference:
            if not matches(args.reference):
                raise RuntimeError('reviewed_module_scope_ui_changed')
        elif not args.expect or args.expect not in before:
            raise RuntimeError('expected_manager_ui_missing')
        if args.tap:
            adb('shell', 'input tap ' + ' '.join(args.tap))
        if args.text:
            adb('shell', 'input text ' + args.text)
    xml = dump()
    (OUT / (args.step + '.xml')).write_text(xml, encoding='utf-8')
    shot = Image.open(io.BytesIO(adb('exec-out', 'screencap -p')))
    # Exclude system status/notification content from retained task evidence.
    shot.paste((245,245,245), (0,0,shot.width,240))
    shot.save(OUT / (args.step + '.png'))
    for node in ET.fromstring(xml).iter('node'):
        if node.get('text') or node.get('content-desc'):
            print({k: node.get(k) for k in ('text','content-desc','resource-id','checked','bounds')})
    record['result'] = 'observed'
finally:
    if owned:
        try:
            adb('shell', 'rm -f /data/local/tmp/yj-finder-scope.xml')
        finally:
            if json.loads(LOCK.read_text()).get('lease') == lease['lease']:
                LOCK.unlink()
    (OUT / (args.step + '.json')).write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
