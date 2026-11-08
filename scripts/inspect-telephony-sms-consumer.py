"""Inspect only the saved native cloudservice candidate; never open any account or SMS data."""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import re
import zipfile
from loguru import logger
logger.remove()
from androguard.core.axml import AXMLPrinter
from androguard.core.dex import DEX

root = Path(__file__).resolve().parent.parent
apk = root / 'captures/calls-sms-20261108/b59f8656/com.miui.cloudservice/base.apk'
android = '{http://schemas.android.com/apk/res/android}'
records = []
settings = []
with zipfile.ZipFile(apk) as z:
    manifest = AXMLPrinter(z.read('AndroidManifest.xml')).get_xml_obj()
    app = manifest.find('application')
    components = []
    for kind in ['activity', 'service', 'provider', 'receiver']:
        for e in app.findall(kind):
            if re.search(r'sms|mms|message|continuity|relay', str(e.attrib), re.I) or any(re.search(r'sms|mms|message|continuity|relay', str(x.attrib), re.I) for x in e.iter()):
                components.append({'kind': kind, 'attributes': dict(e.attrib), 'actions': [dict(x.attrib) for x in e.findall('.//action')]})
    for name in z.namelist():
        if not re.fullmatch(r'classes\d*\.dex', name):
            continue
        raw = z.read(name)
        for cls in DEX(raw).get_classes():
            cn = cls.get_name()
            if cn in {'LW2/d;', 'Lp2/a;', 'Lcom/miui/cloudservice/mms/MmsSyncSettingsActivity;'}:
                settings.append({'class': cn, 'super': cls.get_superclassname(), 'dex': name,
                                 'dex_sha256': hashlib.sha256(raw).hexdigest(),
                                 'methods': [{'name': m.get_name(), 'descriptor': m.get_descriptor(),
                                              'instructions': [f'{off:04x}: {i.get_name()} {i.get_output()}' for off, i in m.get_instructions_idx()]} for m in cls.get_methods()]})
            if not re.search(r'sms|mms|continuity|relay|messagelist', cn, re.I) or cn.startswith(('Lkotlin', 'Landroidx/', 'Lcom/google/')):
                continue
            methods = []
            for m in cls.get_methods():
                refs = [f'{off:04x}: {i.get_name()} {i.get_output()}' for off, i in m.get_instructions_idx()
                        if re.search(r'sms|message|continuity|relay|startActivity|loadUrl', i.get_output(), re.I)]
                methods.append({'name': m.get_name(), 'descriptor': m.get_descriptor(), 'references': refs})
            records.append({'class': cn, 'super': cls.get_superclassname(), 'dex': name,
                            'dex_sha256': hashlib.sha256(raw).hexdigest(), 'methods': methods})
result = {'utc': datetime.now(timezone.utc).isoformat(), 'command': 'python scripts/inspect-telephony-sms-consumer.py',
          'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
          'version': manifest.get(android + 'versionName'), 'version_code': manifest.get(android + 'versionCode'),
          'components': components, 'classes': records, 'settings_activity_path': settings,
          'scope': 'Static saved cloudservice candidate only. No launch/account/network/record query. Class search absence does not prove the whole system lacks another SMS consumer.'}
out = root / 'build/calls-sms-validation/repair/sms-consumer-inspection.json'
out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print('Saved', len(components), 'candidate manifest components and', len(records), 'matching DEX classes')
