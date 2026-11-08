"""Original DEX evidence for exact-ID limitations and the native identifier selection guard."""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import zipfile
from loguru import logger
logger.remove()
from androguard.core.dex import DEX

root = Path(__file__).resolve().parent.parent
apk = root / 'captures/calls-sms-20261108/3B161Q00YN200000/com.android.mms/base.apk'
targets = {
    'Lcom/oplus/mms/communicationshare/operation/InsertSmsToTelephonyAction;': {'hasSameRemoteSmsMessage', 'executeAction'},
    'Lcom/oplus/mms/communicationshare/operation/IdConvertHelper;': {'d'},
}
methods = []
with zipfile.ZipFile(apk) as z:
    for name in z.namelist():
        if not name.endswith('.dex'):
            continue
        raw = z.read(name)
        for cls in DEX(raw).get_classes():
            if cls.get_name() not in targets:
                continue
            for m in cls.get_methods():
                if m.get_name() not in targets[cls.get_name()]:
                    continue
                methods.append({'class': cls.get_name(), 'name': m.get_name(), 'descriptor': m.get_descriptor(),
                                'dex': name, 'dex_sha256': hashlib.sha256(raw).hexdigest(),
                                'instructions': [f'{off:04x}: {i.get_name()} {i.get_output()}' for off, i in m.get_instructions_idx()]})
assert len(methods) == 3
def body(name):
    return '\n'.join(s for m in methods if m['name'] == name for s in m['instructions'])
assert 'identifier IN (' in body('d')
assert 'date = ? AND type = ?' in body('hasSameRemoteSmsMessage')
assert 'body' in body('hasSameRemoteSmsMessage') and 'address' in body('hasSameRemoteSmsMessage')
assert '->f0(' in body('executeAction') and 'Landroid/net/Uri;' in body('executeAction')
assert 'hasSameRemoteSmsMessage' in body('executeAction') and 'Ljava/lang/Object;-><init>' in body('executeAction')
result = {'utc': datetime.now(timezone.utc).isoformat(), 'command': 'python scripts/verify-telephony-sms-import-path.py',
          'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
          'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'methods': methods,
          'findings': ['OEM identifier lookup constructs an interpolated identifier IN selection; local identifier quote/control guard is required before invoking this action',
                       'Native sent-copy action deduplicates by date/body/address and discards the insertion URI; its Object result cannot attest an exact cross-device message ID'],
          'scope': '3 original DEX methods only; no provider/database access, SMS insert, message contents, cloud, send or device action.'}
out = root / 'build/calls-sms-validation/repair/sms-import-native-evidence.json'
out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print('PASS: 3 native DEX methods verify identifier SQL boundary and non-identifying sent-copy result; no SMS action')
