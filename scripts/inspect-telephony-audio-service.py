"""Read the newly captured native service, never bind/start/capture audio."""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import re
import zipfile
from loguru import logger
logger.remove()
from androguard.core.dex import DEX
from androguard.core.axml import AXMLPrinter

root = Path(__file__).resolve().parent.parent
apk = root / 'captures/calls-sms-20261108/b59f8656/com.miui.audiomonitor/base.apk'
android = '{http://schemas.android.com/apk/res/android}'
with zipfile.ZipFile(apk) as z:
    manifest = AXMLPrinter(z.read('AndroidManifest.xml')).get_xml_obj()
    app = manifest.find('application')
    records = []
    for name in z.namelist():
        if not re.fullmatch(r'classes\d*\.dex', name):
            continue
        raw = z.read(name)
        for cls in DEX(raw).get_classes():
            cn = cls.get_name()
            if not re.search(r'distaudio|Relay|Socket|AudioRecord|AudioTrack', cn, re.I):
                continue
            methods = []
            for m in cls.get_methods():
                methods.append({'name': m.get_name(), 'descriptor': m.get_descriptor(),
                                'instructions': [f'{off:04x}: {i.get_name()} {i.get_output()}' for off, i in m.get_instructions_idx()]})
            records.append({'class': cn, 'super': cls.get_superclassname(), 'dex': name,
                            'dex_sha256': hashlib.sha256(raw).hexdigest(), 'methods': methods})
    result = {'utc': datetime.now(timezone.utc).isoformat(), 'command': 'python scripts/inspect-telephony-audio-service.py',
              'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'apk': str(apk), 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
              'version': manifest.get(android + 'versionName'), 'versionCode': manifest.get(android + 'versionCode'),
              'sharedUid': manifest.get(android + 'sharedUserId'), 'application': dict(app.attrib),
              'permissions': [e.get(android + 'name') for e in manifest.findall('uses-permission')],
              'services': [{'attributes': dict(s.attrib), 'intents': [dict(x.attrib) for x in s.findall('.//action')]} for s in app.findall('service')],
              'classes': records, 'scope': 'Original APK static inspection only; no runtime/audio/route result.'}
    dest = root / 'build/calls-sms-validation/repair/audio-service-inspection.json'
    dest.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print('Saved', len(records), 'native audio classes; static inspection only')
