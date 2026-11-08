"""Audit actual native audio paths in saved APKs. No device/call/microphone access."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import zipfile
from loguru import logger
logger.remove()
from androguard.core.dex import DEX

root = Path(__file__).resolve().parent.parent
cases = [
    ('xiaomi-phone', 'captures/calls-sms-20261108/b59f8656/com.android.phone/base.apk', {
        'Lcom/android/services/telephony/relay/phonecall/RelayUtils$Relay;': {'startRelayAudioService', 'stopRelayAudioService'},
        'Lcom/android/services/telephony/relay/phonecall/relayaudio/DistAudioDeviceProvider;': {
            'createDistAudioRouteForDevice', 'connectDistAudioDevice', 'disconnectDistAudioDevice', 'getCurDistAudioRoute'},
        'Lcom/xiaomi/distaudiokit/MiAudioManager;': {'bindAndWaitForConnectedService'},
        'Lcom/miui/audiomonitor/distaudio/IDistAudioService;': {
            'connectDistAudioDevice', 'disconnectDistAudioDevice', 'getDistAudioDevices', 'getCurDistAudioRoute'},
    }),
    ('oplus-nearcomm', 'captures/device-interconnect-20261108/coloros17/com.oplus.nearcomm/OplusNearComm.apk', {
        'Lcom/oplus/nearcomm/call/NearCallTransfer;': {'lambda$onSetRoute$4', 'isDeviceExists', 'canRequestBluetoothAudio'},
        'Lcom/oplus/nearcomm/NearConstants;': {'getRemoteRouteByDeviceType', 'isRemoteRoute'},
        'Lcom/oplus/nearcomm/call/NearInCallService;': {'onCallAudioStateChanged'},
    }),
]
results = []
for label, relative, targets in cases:
    apk = root / relative
    records, hashes = [], {}
    with zipfile.ZipFile(apk) as z:
        for dn in z.namelist():
            if not dn.endswith('.dex'):
                continue
            raw = z.read(dn)
            found = False
            for cls in DEX(raw).get_classes():
                if cls.get_name() not in targets:
                    continue
                for m in cls.get_methods():
                    if m.get_name() not in targets[cls.get_name()]:
                        continue
                    found = True
                    records.append({'class': cls.get_name(), 'method': m.get_name(), 'descriptor': m.get_descriptor(),
                                    'instructions': [f'{offset:04x}: {i.get_name()} {i.get_output()}' for offset, i in m.get_instructions_idx()]})
            if found:
                hashes[dn] = hashlib.sha256(raw).hexdigest()
    assert len(records) == sum(map(len, targets.values())), (label, len(records))
    results.append({'label': label, 'apk': str(apk), 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                    'dex_sha256': hashes, 'methods': records})

def body(label, name):
    return '\n'.join(line for r in results if r['label'] == label for m in r['methods'] if m['method'] == name for line in m['instructions'])

assert 'com.miui.audiomonitor' in body('xiaomi-phone', 'startRelayAudioService')
assert 'startForegroundService' in body('xiaomi-phone', 'startRelayAudioService')
assert 'setPlayerFormat' in body('xiaomi-phone', 'createDistAudioRouteForDevice')
assert 'setRecorderFormat' in body('xiaomi-phone', 'createDistAudioRouteForDevice')
assert 'getSupportedBluetoothDevices' in body('oplus-nearcomm', 'isDeviceExists')
assert 'isHoldOrActive' in body('oplus-nearcomm', 'canRequestBluetoothAudio')
assert 'requestBluetoothAudio' in body('oplus-nearcomm', 'lambda$onSetRoute$4')
route = body('oplus-nearcomm', 'getRemoteRouteByDeviceType')
assert '13' in route and '14' in route
output = root / 'build/calls-sms-validation/round2-audio-signatures.json'
ledger = {'utc': datetime.now(timezone.utc).isoformat(), 'command': 'python scripts/verify-telephony-audio.py',
          'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'module_apk': None,
          'scope': 'Static saved native SDK and nearcomm branches only; no voice media, route switch, service bind or device execution',
          'findings': ['Xiaomi native audio uses external com.miui.audiomonitor service; service implementation APK not captured',
                       'Oplus nearcomm uses actual system-supported Bluetooth device; mapped native routes are iPhone/watch (13/14)',
                       'These branches do not establish cross-brand Android phone-pad PCM or audio permissions'],
          'cases': results}
output.write_text(json.dumps(ledger, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'PASS: {sum(len(r["methods"]) for r in results)} actual audio signatures/branches; no audio-flow claim')
