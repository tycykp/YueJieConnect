"""Read-only checks against the two captured OEM APKs; no installation or device calls."""
import hashlib
import json
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
root = Path(__file__).resolve().parents[1]
S = 'Ljava/lang/String;'
O = 'Lcom/oplus/linker/synergy/Fileservice$'
H = 'Lcom/oplus/linker/synergy/wisetransfer/fileservice/'
X = 'Lcom/xiaomi/mirror/message/'
required = {
    'hyperos4/com.xiaomi.mirror/MirrorOS4.apk': (
        '9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356', {
            X+'CrossFileShareMessage;': {'getSessionId()J', 'getClassType()I', f'getStateMsg(IJ){X}CrossFileShareMessage;'},
            'Lq3/i;': {f'w({X}Message;{S})V'},
            'Lv3/b;': {f'n({X}CrossFileShareMessage;{S})V', 'm(J)V'},
            'Lb4/q;': {'q(Lcom/xiaomi/continuity/channel/Channel;Lcom/xiaomi/continuity/channel/Packet;)V', 'r(Lcom/xiaomi/continuity/channel/Channel;Lcom/xiaomi/continuity/channel/Packet;Lcom/xiaomi/continuity/channel/PacketTransferProgress;)V'},
        }),
    'coloros17/com.oplus.linker/OPSynergy.apk': (
        'dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b', {
            O+'FileInfo;': {f'getUri(){S}', f'getFilepath(){S}', 'getLength()J', 'getFlags()J'},
            O+'FileData;': {f'newBuilder(){O}FileData$Builder;'},
            O+'FileData$Builder;': {f'setInfo({S}){O}FileData$Builder;', f'setLength(J){O}FileData$Builder;', f'setBuffer(Lcom/google/protobuf/ByteString;){O}FileData$Builder;', f'setFlag(J){O}FileData$Builder;', f'setUri({S}){O}FileData$Builder;', f'build(){O}FileData;'},
            O+'FileStatus;': {'getStatus()I', 'getLength()J'},
            H+'FileServiceHelper;': {'<init>()V', f'writeFileBegin({S}{H}icdf/FileServiceImpl$InvokerStatus;)V', f'writeFile(Landroid/content/Context;{O}FileData;{H}FileServiceProgressCallBack;J){S}', f'writeFileEnd(I){S}'},
            H+'icdf/FileServiceImpl$InvokerStatus;': {f'notifyStatus({O}FileStatus;)Z', 'shutdown()Z'},
        }),
}
fields = {
    X+'CrossFileShareMessage;': {'action': 'I', 'clipData': X+'ClipDataMessage;'},
    X+'ClipDataMessage;': {'fileList': 'Ljava/util/List;'},
    X+'ClipDataMessage$FileInfo;': {'url': S, 'name': S, 'size': 'J'},
}
evidence = []
for relative, (expected_hash, wanted) in required.items():
    apk = root / 'captures/device-interconnect-20261108' / relative
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == expected_hash, 'Unexpected OEM APK'
    found = set()
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.namelist():
            if not entry.startswith('classes') or not entry.endswith('.dex'):
                continue
            data = archive.read(entry)
            for cls in DEX(data).get_classes():
                name = cls.get_name()
                if name not in wanted and name not in fields:
                    continue
                methods = {m.get_name()+m.get_descriptor().replace(' ', ''): m for m in cls.get_methods()}
                assert wanted.get(name, set()) <= methods.keys(), (name, wanted.get(name, set()) - methods.keys())
                actual_fields = {f.get_name(): f.get_descriptor() for f in cls.get_fields()}
                for field, typ in fields.get(name, {}).items():
                    assert actual_fields.get(field) == typ, (name, field)
                selected = sorted(wanted.get(name, set()))
                evidence.append(dict(apk=relative, apk_sha256=expected_hash, dex=entry,
                    dex_sha256=hashlib.sha256(data).hexdigest(), class_name=name, methods=selected,
                    fields=fields.get(name, {}),
                    instructions={sig: [f'{i.get_name()} {i.get_output()}' for i in methods[sig].get_instructions()]
                                  for sig in selected if name == 'Lv3/b;'}))
                found.add(name)
    assert wanted.keys() <= found, wanted.keys() - found
    if relative.startswith('hyperos4'):
        assert fields.keys() <= found, fields.keys() - found
out = root / 'build/file-transfer-validation/native-signatures.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'PASS: {len(evidence)} OEM classes, {sum(len(e["methods"]) for e in evidence)} methods, exact APK/DEX hashes and metadata fields')
