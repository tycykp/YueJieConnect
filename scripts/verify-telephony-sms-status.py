"""Check new status hooks/action/query against captured DEX; never open real SMS storage."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import zipfile
from loguru import logger
logger.remove()
from androguard.core.dex import DEX
from androguard.core.axml import AXMLPrinter

root = Path(__file__).resolve().parent.parent
apk = root / 'captures/calls-sms-20261108/3B161Q00YN200000/com.android.mms/base.apk'
targets = {
    'Lcom/android/mms/datamodel/DataModel;': {'r', 'v'},
    'Lcom/android/mms/datamodel/p;': {'n'},
    'Lcom/android/mms/datamodel/e;': {'y1'},
    'Lcom/android/mms/datamodel/action/Action;': {'start'},
    'Lcom/android/mms/datamodel/data/MessageData;': {'getStatus'},
    'Lcom/oplus/mms/communicationshare/operation/UpdateMessageStatusAction;': {'<init>', 'executeAction'},
}
records, constants, dex_hashes = [], {}, {}
with zipfile.ZipFile(apk) as z:
    android = '{http://schemas.android.com/apk/res/android}'
    manifest = AXMLPrinter(z.read('AndroidManifest.xml')).get_xml_obj()
    application = manifest.find('application')
    host = {'package': manifest.get('package'), 'version_code': manifest.get(android + 'versionCode'),
            'version_name': manifest.get(android + 'versionName'), 'shared_uid': manifest.get(android + 'sharedUserId'),
            'application': application.get(android + 'name'), 'process': application.get(android + 'process'),
            'permissions': [p.get(android + 'name') for p in manifest.findall('uses-permission')]}
    assert host['package'] == 'com.android.mms' and host['shared_uid'] is None
    for name in z.namelist():
        if not name.endswith('.dex'):
            continue
        raw = z.read(name)
        dex = DEX(raw)
        found = False
        for cls in dex.get_classes():
            if cls.get_name() not in targets:
                continue
            if cls.get_name().endswith('/MessageData;'):
                for field in cls.get_fields():
                    if field.get_name().startswith(('BUGLE_STATUS_OUTGOING_', 'PROTOCOL_')):
                        value = field.get_init_value()
                        constants[field.get_name()] = value.get_value() if value else None
            for method in cls.get_methods():
                if method.get_name() not in targets[cls.get_name()]:
                    continue
                found = True
                records.append({'class': cls.get_name(), 'method': method.get_name(), 'descriptor': method.get_descriptor(),
                                'flags': method.get_access_flags_string(), 'dex': name,
                                'instructions': [f'{offset:04x}: {i.get_name()} {i.get_output()}' for offset, i in method.get_instructions_idx()]})
        if found:
            dex_hashes[name] = hashlib.sha256(raw).hexdigest()

def method(cls, name, descriptor=None):
    matches = [r for r in records if r['class'] == cls and r['method'] == name
               and (descriptor is None or r['descriptor'].replace(' ', '') == descriptor)]
    assert len(matches) == 1, (cls, name, descriptor, len(matches))
    return matches[0]

prefix = 'Lcom/android/mms/datamodel/'
method(prefix + 'DataModel;', 'r', '()Lcom/android/mms/datamodel/DataModel;')
method(prefix + 'DataModel;', 'v', '()Lcom/android/mms/datamodel/p;')
query = method(prefix + 'p;', 'n', '(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;')
assert 'public' in query['flags']
mutation = method(prefix + 'e;', 'y1', '(Lcom/android/mms/datamodel/p;Ljava/lang/String;Landroid/content/ContentValues;)Z')
assert 'message_status' not in '\n'.join(mutation['instructions']) or 'messages' in '\n'.join(mutation['instructions'])
method(prefix + 'action/Action;', 'start', '()V')
method('Lcom/oplus/mms/communicationshare/operation/UpdateMessageStatusAction;', '<init>', '(Ljava/lang/String;II)V')
body = '\n'.join(method('Lcom/oplus/mms/communicationshare/operation/UpdateMessageStatusAction;', 'executeAction')['instructions'])
assert 'IdConvertHelper;->e' in body and 'SendMessageAction;->updateMessageAndStatus' in body
assert 'ProcessDeliveryReportAction;->deliveryReportReceived' in body and 'ResendMessageAction' in body
assert constants['PROTOCOL_SMS'] == 0
assert [constants['BUGLE_STATUS_OUTGOING_' + key] for key in ['COMPLETE', 'FAILED', 'FAILED_EMERGENCY_NUMBER']] == [1, 8, 9]
assert [constants['BUGLE_STATUS_OUTGOING_' + key] for key in ['YET_TO_SEND', 'SENDING', 'RESENDING', 'AWAITING_RETRY']] == [4, 5, 6, 7]
result = {'utc': datetime.now(timezone.utc).isoformat(), 'command': 'python scripts/verify-telephony-sms-status.py',
          'system_apk': str(apk), 'system_apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
          'module_apk': None, 'host': host, 'dex_sha256': dex_hashes, 'constants': constants, 'methods': records,
          'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
          'scope': 'Saved DEX signatures/constants only; no native storage access, SMS, device or APK install'}
output = root / 'build/calls-sms-validation/sms-native/native-status-signatures.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'PASS: {len(records)} exact status/query/action methods and OEM SMS constants')
