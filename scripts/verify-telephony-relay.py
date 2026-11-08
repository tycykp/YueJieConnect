"""Validate newly used HyperOS native relay declarations/behavior against the original DEX only."""
from pathlib import Path
import hashlib
import json
import re
import zipfile
from datetime import datetime, timezone
from loguru import logger
logger.remove()
from androguard.core.dex import DEX
from androguard.core.axml import AXMLPrinter

root = Path(__file__).resolve().parent.parent
apk = root / 'captures/calls-sms-20261108/b59f8656/com.android.phone/base.apk'
output = root / 'build/calls-sms-validation/relay'
output.mkdir(parents=True, exist_ok=True)
prefix = 'Lcom/android/services/telephony/relay/phonecall/'
targets = {
    'RelayConnectionService': {'onCreateIncomingConnection': '(Landroid/telecom/PhoneAccountHandle;Landroid/telecom/ConnectionRequest;)Landroid/telecom/Connection;'},
    'RelayConnection': {'onAnswer': '(I)V', 'onReject': ['()V', '(Ljava/lang/String;)V'],
                        'onDisconnect': '()V', 'onHold': '()V', 'onUnhold': '()V', 'onAbort': '()V',
                        'forceDisconnected': '(Landroid/telecom/DisconnectCause;)V', 'tearDown': '()V'},
    'RelayUtils$Relay': {'padSupportRelay': '(Landroid/content/Context;)Ljava/lang/String;',
                         'getRelayManager': '()' + prefix + 'RelayManager;', 'isRelaySide': '()Z'},
    'RelayManager': {'enableRelayPhoneAccount': '(Z)V', 'getRelayConnectionsManager': '()' + prefix + 'RelayConnectionsManager;'},
    'RelayConnectionsManager': {'getRelayConnections': '()Ljava/util/Set;'},
    'PadContinuityController': {'getInstance': '()' + prefix + 'PadContinuityController;',
                                'getChannelInRelay': '()' + prefix + 'RelayChannelInfo;', 'init': '()V'},
    'message/RelayMessageHandler': {'handleRinging': '(' + prefix + 'message/request/RelayRequestRing;)V'},
}
records = []
found = set()
field_found = False
apk_sha = hashlib.sha256(apk.read_bytes()).hexdigest()
assert apk_sha == '8b057de52982aee24ce8877918592dba95b6af3f4e8934bac074741746acf319'
with zipfile.ZipFile(apk) as z:
    manifest = AXMLPrinter(z.read('AndroidManifest.xml')).get_xml_obj()
    android = '{http://schemas.android.com/apk/res/android}'
    service = next(s for s in manifest.findall('application/service')
                   if s.get(android + 'name') == 'com.android.services.telephony.relay.phonecall.RelayConnectionService')
    assert service.get(android + 'permission') == 'android.permission.BIND_TELECOM_CONNECTION_SERVICE'
    assert service.get(android + 'exported') == 'true'
    for name in z.namelist():
        if not re.fullmatch(r'classes\d*\.dex', name):
            continue
        raw = z.read(name)
        for cls in DEX(raw).get_classes():
            if cls.get_name() == prefix + 'RelayPhoneAccountRegistry;':
                field_found = any(f.get_name() == 'PHONE_ACCOUNT_HANDLE' and f.get_descriptor() == 'Landroid/telecom/PhoneAccountHandle;' for f in cls.get_fields())
            short = cls.get_name()[len(prefix):-1] if cls.get_name().startswith(prefix) else ''
            if short not in targets:
                continue
            for method in cls.get_methods():
                expected = targets[short].get(method.get_name())
                if expected is None:
                    continue
                expected = expected if isinstance(expected, list) else [expected]
                descriptor = method.get_descriptor().replace(' ', '')
                if descriptor not in expected:
                    continue
                instructions = [f'{i:04x}: {ins.get_name()} {ins.get_output()}' for i, ins in method.get_instructions_idx()]
                records.append({'class': cls.get_name(), 'super': cls.get_superclassname(), 'method': method.get_name(),
                                'descriptor': descriptor, 'access': method.get_access_flags_string(), 'instructions': instructions,
                                'dex': name, 'dex_sha256': hashlib.sha256(raw).hexdigest()})
                found.add((short, method.get_name(), descriptor))
expected_all = {(cls, name, desc) for cls, methods in targets.items() for name, types in methods.items()
                for desc in (types if isinstance(types, list) else [types])}
assert found == expected_all, expected_all - found
assert field_found
ring = next(r for r in records if r['method'] == 'handleRinging')
assert any('->addNewIncomingCall' in line for line in ring['instructions'])
assert any('telecomm.EXTRA_RELAY_CALL' in line for line in ring['instructions'])
assert not any('APPLE' in line for line in ring['instructions'])
for method in ('onAnswer', 'onReject', 'onDisconnect', 'onHold', 'onUnhold'):
    for record in [r for r in records if r['method'] == method]:
        assert any('->sendOperateRequest' in line for line in record['instructions'])
ledger = {'utc': datetime.now(timezone.utc).isoformat(), 'command': 'python scripts/verify-telephony-relay.py',
          'apk': str(apk), 'apk_sha256': apk_sha, 'sharedUserId': manifest.get(android + 'sharedUserId'),
          'package_version': manifest.get(android + 'versionName'), 'package_version_code': manifest.get(android + 'versionCode'),
          'scope': 'Original APK static declarations/bytecode, no runtime/device/call action', 'methods': records,
          'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
(output / 'native-signatures.json').write_text(json.dumps(ledger, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'PASS: {len(records)} original DEX declarations; native Android relay incoming/control branch confirmed')
