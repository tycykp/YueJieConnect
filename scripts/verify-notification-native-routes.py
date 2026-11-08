"""Incremental original-DEX evidence: PC-only Mirror export and Oplus phone/pad policy provider.

Reuses the existing APK hash ledger and parses only the DEX files for newly inspected classes.
No devices, notification content, settings writes, or claimed native delivery.
"""
import hashlib
import json
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
root = Path(__file__).resolve().parents[1]
prior = json.loads((root / 'build/notifications-validation/native-samples.json').read_text(encoding='utf-8'))
specs = {
    'xiaomi': ('captures/device-interconnect-20261108/hyperos4/com.xiaomi.mirror/MirrorOS4.apk', {
        'classes2.dex': {
            'Lcom/xiaomi/mirror/connection/G;': {'G0', 'h'},
            'Lcom/xiaomi/mirror/message/MessageHandlerImpl;': {'handleMessage'},
        },
        'classes.dex': {
            'Lcom/xiaomi/mirror/l0;': {'i0', 'k0', 'm0'},
        }}),
    'oplus': ('captures/device-interconnect-20261108/coloros17/com.oplus.linker/OPSynergy.apk', {
        'classes2.dex': {
            'Lcom/oplus/linker/synergy/common/settings/DependSettingValueProxy;': {'getPadNoticeToggleState', 'setPadNoticeToggleState'},
            'Lzd;': {'<clinit>', 'a'},
            'Lae;': {'<clinit>'},
            'Lcom/oplus/linker/synergy/provider/SpeechActiveProvider;': {'call'},
            'Lcom/oplus/linker/synergy/ui/fragment/PadLinkFragmentOnPhone;': {'onClickToggle'},
        },
        'classes6.dex': {
            'Lcom/oplus/linker/appfunction/LegacyTabletStateRepository;': {'isSyncNotificationEnabled', 'setSyncNotificationEnabled'},
            'Lcom/oplus/linker/synergy/provider/SpeechApiCallImpl;': {'apiScreenNoticeSync', 'setNoticeSwitchState'},
        }}),
}
evidence = {}
for brand, (relative, dexes) in specs.items():
    apk = root / relative
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    assert digest == prior[brand]['apk_sha256'], 'Changed OEM sample requires a new baseline.'
    record = dict(apk=relative, apk_sha256=digest, dex=[], classes={})
    with zipfile.ZipFile(apk) as archive:
        for dex_name, wanted in dexes.items():
            raw = archive.read(dex_name)
            record['dex'].append(dict(name=dex_name, sha256=hashlib.sha256(raw).hexdigest()))
            for cls in DEX(raw).get_classes():
                name = cls.get_name()
                if name not in wanted:
                    continue
                methods = {m.get_name() + m.get_descriptor().replace(' ', ''):
                           [i.get_name() + ' ' + i.get_output() for i in m.get_instructions()]
                           for m in cls.get_methods() if m.get_name() in wanted[name]}
                assert wanted[name] <= {key.split('(')[0] for key in methods}, name
                record['classes'][name] = dict(dex=dex_name, methods=methods,
                    fields=[f.get_name() + ':' + f.get_descriptor() for f in cls.get_fields()])
            assert wanted.keys() <= record['classes'].keys(), set(wanted) - record['classes'].keys()
    evidence[brand] = record

def method(brand, cls, name, descriptor=''):
    methods = evidence[brand]['classes'][cls]['methods']
    matches = [value for key, value in methods.items() if key.startswith(name + '(') and (not descriptor or descriptor in key)]
    assert len(matches) == 1, (cls, name, descriptor)
    return '\n'.join(matches[0])

terminal = method('xiaomi', 'Lcom/xiaomi/mirror/l0;', 'i0')
assert all(value in terminal for value in ('Windows', 'Mac', 'CommonPc')) and 'AndroidPad' not in terminal
assert '->i0()Z' in method('xiaomi', 'Lcom/xiaomi/mirror/connection/G;', 'h', '(Lcom/xiaomi/mirror/l0;)Z')
assert '->p Ljava/util/function/Predicate;' in method('xiaomi', 'Lcom/xiaomi/mirror/connection/G;', 'G0')
handler = method('xiaomi', 'Lcom/xiaomi/mirror/message/MessageHandlerImpl;', 'handleMessage')
assert 'NotificationInteractionMessage' in handler and 'NotificationPostMessage' not in handler
policy = method('oplus', 'Lcom/oplus/linker/synergy/common/settings/DependSettingValueProxy;', 'getPadNoticeToggleState')
assert 'smart_connection_notification_sync_pad' in policy
assert 'GET_secure' in method('oplus', 'Lzd;', '<clinit>')
assert 'com.oplus.appplatform.settings' in method('oplus', 'Lae;', '<clinit>')
assert 'a:Landroid/net/Uri;' in evidence['oplus']['classes']['Lzd;']['fields']
toggle = method('oplus', 'Lcom/oplus/linker/synergy/ui/fragment/PadLinkFragmentOnPhone;', 'onClickToggle', '(IZ)V')
assert 'Notice_CrossDevice' in toggle and 'setPadNoticeSwitchState' in toggle
speech = method('oplus', 'Lcom/oplus/linker/synergy/provider/SpeechApiCallImpl;', 'apiScreenNoticeSync')
assert 'isOpen' in speech and 'setNoticeSwitchState' in speech and 'StatusBarNotification' not in speech

evidence['scope'] = ('Original OEM DEX and switch-provider signatures only. Mirror G0 selects PC types, not AndroidPad. '
                     'Oplus provider and appfunction describe policy switches, not a notification payload/provider. '
                     'No new native Android notification sink or successful device execution is inferred.')
evidence['script_sha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
evidence['module_sources'] = [{ 'path': str(path.relative_to(root)).replace('\\', '/'),
                              'sha256': hashlib.sha256(path.read_bytes()).hexdigest() }
                            for path in [root / 'android/module/src/main/java/io/github/qiangjian/connect/OplusNotificationPolicy.java']]
output = root / 'build/notifications-validation/native-routes-round2.json'
output.write_text(json.dumps(evidence, indent=2, ensure_ascii=False), encoding='utf-8')
print('PASS Mirror PC-only terminal/Android sink boundary and Oplus real phone/pad switch-provider descriptors.')
