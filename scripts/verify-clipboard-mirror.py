"""Verify clipboard-only Mirror DEX descriptors; never contacts a device."""
import hashlib
import json
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
root = Path(__file__).resolve().parents[1]
apk = root / 'captures/device-interconnect-20261108/hyperos4/com.xiaomi.mirror/MirrorOS4.apk'
assert hashlib.sha256(apk.read_bytes()).hexdigest() == '9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356', 'Mirror sample changed'
names = {
    'LV3/V;': {'G', 'T', 'A0', 'm0', 'z0', 'S', 'P'},
    'Lcom/xiaomi/mirror/message/MessageManagerImpl;': {'broadcastAll'},
    'Lcom/xiaomi/mirror/message/ClipboardMessage;': {'<init>', 'getSessionId', 'setSessionId'},
    'Lcom/xiaomi/mirror/message/ClipDataMessage;': {'<init>'},
    'Lcom/xiaomi/mirror/message/ClipDataMessage$Data;': {'<init>'},
    'Lcom/xiaomi/mirror/Mirror;': {'C', 't'},
    'Lcom/google/protobuf/i;': {'m', 'C', 'size'},
}
required = {
    'LV3/V;': {'G()LV3/V;', 'T(Lcom/xiaomi/mirror/k0;Lcom/xiaomi/mirror/message/ClipboardMessage;)V',
              'A0(Landroid/content/ClipData;)Lcom/xiaomi/mirror/message/ClipDataMessage;',
              'm0(Landroid/content/ClipData;)V', 'z0(Lcom/xiaomi/mirror/message/ClipDataMessage;Z)Landroid/content/ClipData;',
              'S()V', 'P(Lcom/xiaomi/mirror/message/ClipDataMessage$Data;)Ljava/lang/CharSequence;'},
    'Lcom/xiaomi/mirror/message/MessageManagerImpl;': {'broadcastAll(Lcom/xiaomi/mirror/message/Message;)V'},
    'Lcom/xiaomi/mirror/message/ClipboardMessage;': {'<init>()V', 'getSessionId()J', 'setSessionId(J)V'},
    'Lcom/xiaomi/mirror/message/ClipDataMessage;': {'<init>()V'},
    'Lcom/xiaomi/mirror/message/ClipDataMessage$Data;': {'<init>()V'},
    'Lcom/xiaomi/mirror/Mirror;': {'C()Lcom/xiaomi/mirror/Mirror;', 't()Landroid/content/ClipboardManager;'},
    'Lcom/google/protobuf/i;': {'m(Ljava/lang/String;Ljava/nio/charset/Charset;)Lcom/google/protobuf/i;', 'C()[B', 'size()I'},
}
required_fields = {
    'Lcom/xiaomi/mirror/message/ClipboardMessage;': {'data:Lcom/xiaomi/mirror/message/ClipDataMessage;'},
    'Lcom/xiaomi/mirror/message/ClipDataMessage;': {'dataList:Ljava/util/List;', 'fileList:Ljava/util/List;', 'clock:J'},
    'Lcom/xiaomi/mirror/message/ClipDataMessage$Data;': {'content:Lcom/google/protobuf/i;', 'type:I', 'url:Ljava/lang/String;', 'textType:Ljava/lang/String;'},
}
evidence = []
with zipfile.ZipFile(apk) as archive:
    for entry in sorted(n for n in archive.namelist() if n.endswith('.dex')):
        data = archive.read(entry)
        for cls in DEX(data).get_classes():
            name = cls.get_name()
            if name not in names:
                continue
            methods = {m.get_name() + m.get_descriptor().replace(' ', ''): m
                       for m in cls.get_methods() if m.get_name() in names[name]}
            assert names[name] <= {m.get_name() for m in methods.values()}, name
            assert required[name] <= methods.keys(), (name, required[name] - methods.keys())
            fields = [f.get_name() + ':' + f.get_descriptor() for f in cls.get_fields()]
            assert required_fields.get(name, set()) <= set(fields), name
            evidence.append(dict(class_name=name, dex=entry,
                                 dex_sha256=hashlib.sha256(data).hexdigest(),
                                 fields=fields,
                                 methods=sorted(methods),
                                 instructions={sig: [i.get_name() + ' ' + i.get_output() for i in m.get_instructions()]
                                               for sig, m in methods.items() if name == 'LV3/V;'}))
assert len(evidence) == len(names), 'Missing clipboard class: ' + str(set(names) - {e['class_name'] for e in evidence})
out = root / 'build/clipboard-validation/mirror-dex.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(dict(apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), classes=evidence), indent=2), encoding='utf-8')
print(f'PASS: pinned Mirror APK, {len(evidence)} classes, {sum(map(len, required.values()))} exact method descriptors and clipboard fields; {out}')
