"""Index newly captured stock ContactsPad call/SMS consumers, without opening it or reading records."""
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
apk = root / 'captures/calls-sms-20261108/b59f8656/com.android.contacts/base.apk'
A = '{http://schemas.android.com/apk/res/android}'
records = []
with zipfile.ZipFile(apk) as z:
    manifest = AXMLPrinter(z.read('AndroidManifest.xml')).get_xml_obj(); app = manifest.find('application')
    components = [{'kind': kind, 'attributes': dict(e.attrib), 'actions': [dict(x.attrib) for x in e.findall('.//action')]}
                  for kind in ['activity', 'service', 'provider', 'receiver'] for e in app.findall(kind)]
    for name in z.namelist():
        if not re.fullmatch(r'classes\d*\.dex', name): continue
        raw = z.read(name)
        for cls in DEX(raw).get_classes():
            cn = cls.get_name(); target = re.search(r'sms|mms|relay|continuity|remote.?message|message.?list', cn, re.I)
            if cn.startswith(('Lkotlin', 'Landroidx/', 'Lcom/google/')): continue
            methods = []
            for m in cls.get_methods():
                instructions = [f'{off:04x}: {i.get_name()} {i.get_output()}' for off, i in m.get_instructions_idx()]
                refs = [line for line in instructions if re.search(r'content://sms|com\.android\.mms|sms:|RelayService|relay\.phonecall|sendSms|Telephony\$Sms', line, re.I)]
                if target or refs:
                    methods.append({'name': m.get_name(), 'descriptor': m.get_descriptor(), 'instructions': instructions if target else refs})
            if methods: records.append({'class': cn, 'super': cls.get_superclassname(), 'dex': name,
                                       'dex_sha256': hashlib.sha256(raw).hexdigest(), 'methods': methods})
result = {'utc': datetime.now(timezone.utc).isoformat(), 'command': 'python scripts/inspect-telephony-contacts-pad.py',
          'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
          'package': manifest.get('package'), 'version': manifest.get(A + 'versionName'), 'version_code': manifest.get(A + 'versionCode'),
          'shared_uid': manifest.get(A + 'sharedUserId'), 'application': dict(app.attrib), 'components': components, 'classes': records,
          'scope': 'Static stock ContactsPad metadata/DEX only. No UI, call log, contacts/SMS content, dial or send action.'}
out = root / 'build/calls-sms-validation/usable/sms-resolution/contacts-pad-inspection.json'
out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print('Saved', len(components), 'components and', len(records), 'candidate native call/SMS classes')
