"""Check the fixed OEM phone Binder registration/transaction and ConnectionService sources."""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import zipfile
from loguru import logger
logger.remove()
from androguard.core.dex import DEX

root = Path(__file__).resolve().parent.parent
apk = root / 'captures/calls-sms-20261108/3B161Q00YN200000/com.android.phone/base.apk'
targets = {
    'Lcom/android/phone/PhoneInterfaceManager;': {'init', 'onTransact'},
    'Lcom/android/services/telephony/TelephonyConnectionService;': {'onCreate', 'onDestroy', 'onConnectionAdded', 'onConnectionRemoved'},
    'Lcom/android/services/telephony/TelephonyConnection;': {'onAnswer', 'onReject', 'onDisconnect', 'onHold', 'onUnhold'},
}
records = []
with zipfile.ZipFile(apk) as z:
    for name in z.namelist():
        if not name.endswith('.dex'): continue
        raw = z.read(name)
        for cls in DEX(raw).get_classes():
            if cls.get_name() not in targets: continue
            for m in cls.get_methods():
                if m.get_name() not in targets[cls.get_name()]: continue
                records.append({'class': cls.get_name(), 'super': cls.get_superclassname(), 'method': m.get_name(), 'descriptor': m.get_descriptor(),
                                'dex': name, 'dex_sha256': hashlib.sha256(raw).hexdigest(),
                                'instructions': [f'{off:04x}: {i.get_name()} {i.get_output()}' for off, i in m.get_instructions_idx()]})
def body(cls, method):
    return '\n'.join(s for r in records if r['class'] == cls and r['method'] == method for s in r['instructions'])
phone = 'Lcom/android/phone/PhoneInterfaceManager;'
assert 'getTelephonyServiceRegisterer' in body(phone, 'init') and '->register(Landroid/os/IBinder;)' in body(phone, 'init')
assert '->sInstance ' in body(phone, 'init')
transaction = [r for r in records if r['class'] == phone and r['method'] == 'onTransact']
assert len(transaction) == 1 and transaction[0]['descriptor'] == '(I Landroid/os/Parcel; Landroid/os/Parcel; I)Z'
assert '5327440' not in body(phone, 'onTransact')  # 0x00514a50, private module-UID-checked proof transaction.
service = 'Lcom/android/services/telephony/TelephonyConnectionService;'
for method, signature in {'onCreate':'()V','onDestroy':'()V','onConnectionAdded':'(Landroid/telecom/Connection;)V','onConnectionRemoved':'(Landroid/telecom/Connection;)V'}.items():
    assert any(r['class'] == service and r['method'] == method and r['descriptor'] == signature for r in records)
connection = 'Lcom/android/services/telephony/TelephonyConnection;'
for method in ['onAnswer','onReject','onDisconnect','onHold','onUnhold']:
    assert body(connection, method), method
result = {'utc': datetime.now(timezone.utc).isoformat(), 'command':'python scripts/verify-telephony-phone-anchor.py',
          'script_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
          'records':records, 'scope':'Original DEX only. Actual system phone Binder owner and native hooks statically checked, not an authenticated device PID or live call test.'}
out=root/'build/calls-sms-validation/usable/phone-anchor/native-signatures.json'
out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print('PASS:',len(records),'native registration/Connection methods; private proof entry does not invoke an OEM radio transaction')
