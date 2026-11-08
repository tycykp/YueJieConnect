"""Verify the actual Android SMS-sharing branch in the captured Oplus Mms classes2.dex."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import zipfile
from loguru import logger
logger.remove()
from androguard.core.dex import DEX

root = Path(__file__).resolve().parent.parent
apk = root / 'captures/calls-sms-20261108/3B161Q00YN200000/com.android.mms/base.apk'
targets = {
    'Lcom/oplus/mms/datamodel/MessageExternalProvider;': {'call', 'e'},
    'Lcom/oplus/mms/communicationshare/operation/SyncOperationManager;': {'d'},
    'Lcom/oplus/mms/communicationshare/operation/OperationWrapper;': {'A0', 'B0', 'K0'},
    'Lcom/oplus/mms/communicationshare/operation/SenderOperation;': {'n', 'p'},
    'Lcom/oplus/mms/communicationshare/operation/ReceiverOperation;': {'sendSms', 'sendSmsV2', 'sendRemoteSms', 'sendRemoteSmsV2', 'updateSendMessageStatus'},
}
records = []
with zipfile.ZipFile(apk) as z:
    raw = z.read('classes2.dex')
    for cls in DEX(raw).get_classes():
        for method in cls.get_methods():
            if method.get_name() not in targets.get(cls.get_name(), set()):
                continue
            records.append({'class': cls.get_name(), 'method': method.get_name(), 'descriptor': method.get_descriptor(),
                            'instructions': [f'{offset:04x}: {i.get_name()} {i.get_output()}' for offset, i in method.get_instructions_idx()]})
assert len(records) == sum(len(names) for names in targets.values())
def body(name):
    return '\n'.join(line for record in records if record['method'] == name for line in record['instructions'])
assert 'update_sms_status' not in body('call')
assert 'not support method:' in body('call')
assert 'getCallingPackage' in body('e')
assert 'InsertNewMessageAction;->insertNewMessage' in body('sendSms')
assert 'InsertSmsToTelephonyAction' in body('sendRemoteSms')
assert 'UpdateMessageStatusAction' in body('updateSendMessageStatus')
assert 'SenderOperation;->p' in body('B0')
assert 'SenderOperation;->n' in body('A0')
ledger = {'utc': datetime.now(timezone.utc).isoformat(), 'command': 'python scripts/verify-telephony-sms-native.py',
          'apk': str(apk), 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
          'dex': 'classes2.dex', 'dex_sha256': hashlib.sha256(raw).hexdigest(),
          'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
          'scope': 'Saved system APK only, no user messages or platform SMS actions', 'methods': records}
(root / 'build/calls-sms-validation/sms-native-signatures.json').write_text(json.dumps(ledger, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'PASS: {len(records)} SMS native methods; unsupported legacy provider and separate send/insert flows verified')
