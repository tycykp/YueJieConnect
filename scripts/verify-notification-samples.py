"""Inspect notification-only OEM DEX signatures. No device access and no private notification data."""
import hashlib
import json
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
root = Path(__file__).resolve().parents[1]
out = root / 'build/notifications-validation/native-samples.json'
specs = {
    'xiaomi': ('captures/device-interconnect-20261108/hyperos4/com.xiaomi.mirror/MirrorOS4.apk', {
        'Lcom/xiaomi/mirror/notification/MirrorNotificationListenerService;': {'onNotificationPosted', 'onNotificationRemoved', 'm'},
        'Lcom/xiaomi/mirror/message/MessageManagerImpl;': {'sendNotificationPostMessage', 'onNotificationRemoved'},
        'Lcom/xiaomi/mirror/message/NotificationPostMessage;': {'getClassType'},
        'Lcom/xiaomi/mirror/message/NotificationInteractionMessage;': {'getClassType'},
        'Lcom/google/protobuf/i;': {'C'},
    }),
    'oplus': ('captures/device-interconnect-20261108/coloros17/com.oplus.linker/OPSynergy.apk', {
        'Lcom/oplus/linker/synergy/common/utils/PadPanelDataBusHelper;': {'handlePadPanelDataRequestPhoneNotice', 'handlePadPanelDataPadNoticeStatus'},
        'Lcom/oplus/linker/synergy/ui/base/NoticeStateChangeHelper;': {'sendNoticeSyncCmd'},
        'Lcom/oplus/linker/feature/ios/client/notification/IPhoneNotificationManager;': set(),
    })
}
evidence = {}
for brand, (path, wanted) in specs.items():
    apk = root / path
    record = dict(apk=str(apk.relative_to(root)), apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), classes=[],
                  notification_listener_subclasses=[], notification_payload_method_owners=[], dex=[])
    with zipfile.ZipFile(apk) as archive:
        for name in sorted(n for n in archive.namelist() if n.endswith('.dex')):
            raw = archive.read(name)
            record['dex'].append(dict(name=name, sha256=hashlib.sha256(raw).hexdigest()))
            for cls in DEX(raw).get_classes():
                cls_name = cls.get_name()
                if cls.get_superclassname() == 'Landroid/service/notification/NotificationListenerService;':
                    record['notification_listener_subclasses'].append(cls_name)
                methods = list(cls.get_methods())
                if any('Landroid/service/notification/StatusBarNotification;' in m.get_descriptor() for m in methods):
                    record['notification_payload_method_owners'].append(cls_name)
                if cls_name not in wanted:
                    continue
                selected = {m.get_name() + m.get_descriptor().replace(' ', ''): m for m in methods if m.get_name() in wanted[cls_name]}
                assert wanted[cls_name] <= {m.get_name() for m in selected.values()}, cls_name
                record['classes'].append(dict(name=cls_name, dex=name,
                    fields=[f.get_name() + ':' + f.get_descriptor() for f in cls.get_fields()], methods=sorted(selected),
                    instructions={sig: [i.get_name() + ' ' + i.get_output() for i in method.get_instructions()]
                                  for sig, method in selected.items()}))
    assert len(record['classes']) == len(wanted), (brand, set(wanted) - {c['name'] for c in record['classes']})
    evidence[brand] = record

# These limits are sample-specific evidence, not a claim about every Oplus ROM or dynamically loaded plugin.
assert not evidence['oplus']['notification_listener_subclasses'], 'Android notification provider found; inspect before adapting.'
evidence['scope'] = 'Captured APK DEX signatures and call instructions only. No APK execution or OEM notification delivery claimed.'
evidence['script_sha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({brand: {k: record[k] for k in ('apk_sha256', 'notification_listener_subclasses', 'notification_payload_method_owners')}
                  for brand, record in evidence.items() if isinstance(record, dict)}, indent=2))
print('PASS notification sample descriptors; evidence: ' + str(out))
