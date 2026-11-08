"""New sender-route signature checks only. Does not rerun the receiver signature ledger."""
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
C = 'Lcom/oplus/linker/synergy/wisetransfer/fileservice/icdf/consumer/'
P = 'Lcom/heytap/accessory/bean/PeerAgent;'
required = {
    'hyperos4/com.xiaomi.mirror/MirrorOS4.apk': ('9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356', {
        'LV3/V;': {'G()LV3/V;', f'M({S})Landroid/net/Uri;',
                   'x0(ILandroid/content/ClipData;)Lcom/xiaomi/mirror/message/ClipDataMessage;',
                   'y0(ILandroid/content/ClipData;Z)Lcom/xiaomi/mirror/message/ClipDataMessage;'},
        'LV3/d0;': {'m(Landroid/net/Uri;)Landroid/net/Uri;', 'o(Landroid/net/Uri;)Landroid/net/Uri;'},
        'Lcom/xiaomi/mirror/d0;': {'onDragStart(Landroid/content/ClipData;III)V'},
        'Lk4/R0;': {'onDrag(Landroid/view/View;Landroid/view/DragEvent;)Z'},
    }),
    'coloros17/com.oplus.linker/OPSynergy.apk': ('dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b', {
        C+'FileServiceConsumer;': {f'onConnectionStateChanged({P}ILvu1;)V', 'getChannel()Lvu1;'},
        P: {f'getAppName(){S}'},
        'Ld83;': {'onNext(Ljava/lang/Object;)V', 'onError(Ljava/lang/Throwable;)V', 'onCompleted()V'},
        O+'FileStatus$Builder;': {f'setStatus(I){O}FileStatus$Builder;', f'setLength(J){O}FileStatus$Builder;', f'build(){O}FileStatus;'},
        C+'FileServiceConsumerImpl$FileHandler;': {f'writeSingleFile({O}FileInfo;)V', 'doTaskFinal(Ljava/lang/Boolean;)V', 'cancelFile()V'},
        C+'FileServiceConsumerImpl;': {'destroy()V'},
        'Lcom/oplus/linker/synergy/FileServiceGrpc$FileServiceStub;': {'writeFile(Ld83;)Ld83;'},
        'Lcom/oplus/linker/synergy/wisetransfer/fileservice/FileServiceUtils;': {
            f'writeFile(Landroid/content/Context;{O}FileInfo;Ld83;Landroid/os/Handler;)V',
            'checkCancelStatus(Ljava/io/InputStream;Landroid/os/Handler;)Z'},
        'Lcom/oplus/linker/synergy/wisetransfer/fileservice/SendFileManager;': {'setCancelState(Z)V'},
        'Lcom/oplus/linker/synergy/wisetransfer/fileservice/scene/MaterialTransferDataBusScene;': {
            'publishDbsMessage(Lcom/oplus/linker/synergy/bus/databus/DataBusMessage;Ljava/lang/String;)V'},
    }),
}
fields = {
    C+'FileServiceConsumer;': {'mPeerAgent': P},
    C+'FileServiceConsumerImpl;': {'mFileConsumer': C+'FileServiceConsumer;'},
    C+'FileServiceConsumerImpl$FileHandler;': {
        'this$0': C+'FileServiceConsumerImpl;', 'mProxy': 'Lcom/oplus/linker/synergy/FileServiceGrpc$FileServiceStub;'},
}
records = []
for relative, (sha, wanted) in required.items():
    apk = root / 'captures/device-interconnect-20261108' / relative
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == sha, 'Unexpected OEM APK'
    found = set()
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.namelist():
            if not entry.startswith('classes') or not entry.endswith('.dex'):
                continue
            data = archive.read(entry)
            for cls in DEX(data).get_classes():
                name = cls.get_name()
                if name not in wanted:
                    continue
                methods = {m.get_name()+m.get_descriptor().replace(' ', ''): m for m in cls.get_methods()}
                assert wanted[name] <= methods.keys(), (name, wanted[name] - methods.keys())
                actual_fields = {f.get_name(): f.get_descriptor() for f in cls.get_fields()}
                for field, descriptor in fields.get(name, {}).items():
                    assert actual_fields.get(field) == descriptor, (name, field)
                records.append(dict(apk=relative, apk_sha256=sha, dex=entry,
                    dex_sha256=hashlib.sha256(data).hexdigest(), class_name=name,
                    fields=fields.get(name, {}), methods=sorted(wanted[name]),
                    instructions={sig: [f'{i.get_name()} {i.get_output()}' for i in methods[sig].get_instructions()]
                                  for sig in sorted(wanted[name])}))
                found.add(name)
    assert wanted.keys() <= found, wanted.keys() - found
out = root / 'build/file-transfer-validation/native-routes.json'
out.write_text(json.dumps({'scope': 'New native sender route/DTO signatures and instructions; no runtime claim',
                          'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                          'records': records}, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'PASS: {len(records)} new route classes / {sum(len(r["methods"]) for r in records)} methods; no device operations')
