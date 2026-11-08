"""Verify new native source/format evidence in original APK DEX, without any live audio."""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import zipfile
from loguru import logger
logger.remove()
from androguard.core.axml import AXMLPrinter
from androguard.core.dex import DEX

root = Path(__file__).resolve().parent.parent
out = root / 'build/calls-sms-validation/repair'
audio = json.loads((out / 'audio-service-inspection.json').read_text('utf-8'))
audio_apk = Path(audio['apk'])
assert hashlib.sha256(audio_apk.read_bytes()).hexdigest() == audio['apk_sha256']
records = []
targets = {
    'Lcom/miui/audiomonitor/distaudio/resource/DistAudioStream;': {'getTelephonyDevice', 'createAudioDownlinkStream', 'createAudioUplinkStream'},
    'Lcom/miui/audiomonitor/distaudio/resource/DistPadAudioStream;': {'openSpeaker'},
    'Lcom/miui/audiomonitor/distaudio/resource/DistPadAudioStream$PadAudioCapture;': {'initRecord'},
}
for cls in audio['classes']:
    for method in cls['methods']:
        if cls['class'] in targets and method['name'] in targets[cls['class']]:
            records.append({'class': cls['class'], **method})
assert len(records) == 5
def body(name):
    return '\n'.join(s for m in records if m['name'] == name for s in m['instructions'])
assert 'getDevices' in body('getTelephonyDevice') and '18' in body('getTelephonyDevice')
assert 'setPreferredDevice' in body('createAudioDownlinkStream')
assert 'setAudioSource' in body('createAudioUplinkStream') and 'const/4 v5, 3' in body('createAudioUplinkStream')
assert '16000' in body('createAudioUplinkStream') and '16000' in body('openSpeaker')
assert 'setAudioSource' in body('initRecord') and '7' in body('initRecord')

apk = root / 'captures/calls-sms-20261108/3B161Q00YN200000/com.android.incallui/base.apk'
expected = {'onBind': '(Landroid/content/Intent;)Landroid/os/IBinder;', 'onUnbind': '(Landroid/content/Intent;)Z',
            'onCallAdded': '(Landroid/telecom/Call;)V', 'onCallRemoved': '(Landroid/telecom/Call;)V', 'onDestroy': '()V'}
android = '{http://schemas.android.com/apk/res/android}'
source = []
with zipfile.ZipFile(apk) as z:
    manifest = AXMLPrinter(z.read('AndroidManifest.xml')).get_xml_obj()
    assert manifest.get(android + 'sharedUserId') == 'android.uid.phone'
    service = next(e for e in manifest.find('application').findall('service') if e.get(android + 'name') == 'com.android.incallui.OplusInCallServiceImpl')
    assert service.get(android + 'permission') == 'android.permission.BIND_INCALL_SERVICE'
    assert service.get(android + 'process') == 'com.android.incallui'
    permissions = {e.get(android + 'name') for e in manifest.findall('uses-permission')}
    assert 'android.permission.CAPTURE_AUDIO_OUTPUT' in permissions
    for name in z.namelist():
        if not name.endswith('.dex'):
            continue
        raw = z.read(name)
        for cls in DEX(raw).get_classes():
            if cls.get_name() != 'Lcom/android/incallui/OplusInCallServiceImpl;':
                continue
            assert cls.get_superclassname() == 'Landroid/telecom/InCallService;'
            for m in cls.get_methods():
                if m.get_name() not in expected:
                    continue
                assert m.get_descriptor() == expected[m.get_name()]
                source.append({'class': cls.get_name(), 'method': m.get_name(), 'descriptor': m.get_descriptor(),
                               'dex': name, 'dex_sha256': hashlib.sha256(raw).hexdigest(),
                               'instructions': [f'{off:04x}: {i.get_name()} {i.get_output()}' for off, i in m.get_instructions_idx()]})
assert len(source) == len(expected)
ledger = {'utc': datetime.now(timezone.utc).isoformat(), 'command': 'python scripts/verify-telephony-audio-endpoints.py',
          'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'module_apk': None,
          'scope': '10 exact OEM DEX methods/manifest. Not runtime UID/PID, grant, route, microphone, call or audible-media verification.',
          'audio_apk_sha256': audio['apk_sha256'], 'audio_inspection_sha256': hashlib.sha256((out / 'audio-service-inspection.json').read_bytes()).hexdigest(),
          'native_audio': records, 'oplus_incall_apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
          'oplus_incall_version': manifest.get(android + 'versionName'), 'oplus_incall_version_code': manifest.get(android + 'versionCode'),
          'oplus_incall_service': dict(service.attrib), 'oplus_incall_source': source}
(out / 'native-audio-endpoints.json').write_text(json.dumps(ledger, ensure_ascii=False, indent=2), encoding='utf-8')
print('PASS: 10 exact native audio/source DEX signatures and protected phone-UID host manifest; no device/audio action')
