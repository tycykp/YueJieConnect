"""Verify new Mirror notification hook descriptors in the original classes2.dex; reuse other APK evidence."""
import hashlib
import json
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
root = Path(__file__).resolve().parents[1]
apk = root / 'captures/device-interconnect-20261108/hyperos4/com.xiaomi.mirror/MirrorOS4.apk'
prior = json.loads((root / 'build/notifications-validation/native-samples.json').read_text(encoding='utf-8'))
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
assert digest == prior['xiaomi']['apk_sha256'], 'Sample changed; old verification cannot be reused.'
listener = 'Lcom/xiaomi/mirror/notification/MirrorNotificationListenerService;'
manager = 'Lcom/xiaomi/mirror/message/MessageManagerImpl;'
required = {
    listener: {'onNotificationPosted(Landroid/service/notification/StatusBarNotification;)V',
               'onNotificationRemoved(Landroid/service/notification/StatusBarNotification;)V',
               'onUnbind(Landroid/content/Intent;)Z'},
    manager: {'sendNotificationPostMessage(Lcom/xiaomi/mirror/message/NotificationPostMessage;)V'}
}
with zipfile.ZipFile(apk) as archive:
    raw = archive.read('classes2.dex')
evidence = []
for cls in DEX(raw).get_classes():
    if cls.get_name() not in required:
        continue
    methods = {m.get_name() + m.get_descriptor().replace(' ', ''): m for m in cls.get_methods()}
    names = required.pop(cls.get_name())
    assert names <= methods.keys(), (cls.get_name(), names - methods.keys())
    evidence.append(dict(name=cls.get_name(), methods={sig: [i.get_name() + ' ' + i.get_output() for i in methods[sig].get_instructions()] for sig in sorted(names)}))
assert not required
source = next(c for c in evidence if c['name'] == listener)['methods']['onNotificationPosted(Landroid/service/notification/StatusBarNotification;)V']
assert any('sendNotificationPostMessage' in instruction for instruction in source), 'Native publish is no longer synchronous inside posted callback.'
assert any('G0()' in instruction for instruction in source), 'Native terminal gate changed; inspect before hooking.'
record = dict(apk_sha256=digest, dex_sha256=hashlib.sha256(raw).hexdigest(), classes=evidence,
              scope='Original APK descriptors and synchronous native publish path. Not hook installation or device delivery.',
              script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
output = root / 'build/notifications-validation/xiaomi-hooks.json'
output.write_text(json.dumps(record, indent=2), encoding='utf-8')
print('PASS 4 native hook descriptors, synchronous listener -> publish, existing G0 terminal gate retained.')
