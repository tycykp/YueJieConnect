"""Pin native request/consent/peer entry points to original OEM DEX, not JADX aliases."""
import hashlib
import json
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
root = Path(__file__).resolve().parents[1]
cast = 'Lcom/oplus/cast/service/'
sdk = cast + 'sdk/'
miui = 'Lcom/miui/circulate/api/'
client = miui + 'protocol/miuiplus/MiuiPlusClient;'
device = sdk + 'DeviceInfo;'
cd = sdk + 'config/CastDevice;'
bundle = 'Landroid/os/Bundle;'
required = {
 'captures/screen-input-20261108/coloros17/HeyCast.apk': (
  '2249716ffc2df561e8dec287d4e59bf02da4dafb19b18fb76c6981ff625cc3a0', {
   cast+'CastService$j;': ({
    'F0('+device+'Lf3/j;'+bundle+')I': ['->x2', '->w2', '->v2'],
    'w2(Lf3/j;'+device+bundle+')V': ['Lf3/j;->C0', 'CastService$j$a;-><init>'],
    'v2(Lf3/j;'+device+bundle+')V': ['->q2', 'Lf3/j;->a'],
    'q2('+device+bundle+'ILjava/lang/String;Ljava/lang/String;)V': ['Handler;->sendMessage'],
    'o2('+cast+'CastService$j;'+device+bundle+'ILjava/lang/String;Ljava/lang/String;)V': ['->q2']}, {}),
   cast+'CastService$j$a;': ({'a()V':['Lf3/j;->a','->o2'], 'g()V':['Lf3/j;->g']}, {}),
   'Lf3/j$a$a;': ({'C0()V':['IBinder;->transact'], 'a()V':['IBinder;->transact'], 'g()V':['IBinder;->transact'],
                   'asBinder()Landroid/os/IBinder;':[]}, {}),
   device: ({'o()Ljava/lang/String;':[], 'l()Ljava/lang/String;':[]}, {}),
  }),
 'captures/device-interconnect-20261108/coloros17/com.oplus.linker/OPSynergy.apk': (
  'dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b', {
   'Lcom/oplus/linker/synergy/thirdapi/heycast/HeycastSdkWrapper;': ({
    'startMirror('+cd+bundle+'Lcom/oplus/linker/synergy/thirdapi/heycast/IHeyCastApi$HeyCastApiCallBack;)V': ['->startMirror']}, {}),
   sdk+'api/HeyCastClientManager;': ({
    'startMirror('+cd+sdk+'api/ScreenRecordAuthorizeCallback;'+bundle+')I': ['Li43;->e', '->startMirrorMoreInfo'],
    'startMirror('+cd+')I': []}, {}),
   'Li43;': ({'e('+cd+')'+device: ['->getUid','->setUid','->getDeviceType','->setDeviceType']}, {}),
   cd: ({'getUid()Ljava/lang/String;':[], 'getDeviceType()Ljava/lang/String;':[]}, {}),
   device: ({'getUid()Ljava/lang/String;':[], 'getDeviceType()Ljava/lang/String;':[]}, {}),
  }),
 'captures/device-interconnect-20261108/hyperos4/com.milink.service/base.apk': (
  '4d4ec36791dcc58b12efde494d24d0a5c8c219213433c1f986996c5277f95bd3', {
   client: ({'circulateService(Ljava/util/List;Ljava/util/List;'+miui+'bean/CirculateParam;)V':
                ['196609','->openRemoteDeviceMirror3']},
             {'mMiuiSynergySdk':'Lcom/xiaomi/mirror/synergy/MiuiSynergySdk;', 'mRelayDevice':miui+'service/CirculateDeviceInfo;'}),
   client[:-1]+'$e;': ({'<init>('+client+'I)V':[]}, {}),
   miui+'protocol/impl/b;': ({}, {'mContext':'Landroid/content/Context;'}),
   miui+'service/CirculateDeviceInfo;': ({'find(I)'+miui+'service/CirculateServiceInfo;':[]}, {'id':'Ljava/lang/String;'}),
   miui+'service/CirculateServiceInfo;': ({}, {'deviceId':'Ljava/lang/String;', 'protocolType':'I'}),
   miui+'bean/CirculateParam;': ({}, {'circulateServiceInfo':miui+'service/CirculateServiceInfo;'}),
   'Lcom/xiaomi/mirror/synergy/MiuiSynergySdk;': ({
    'openRemoteDeviceMirror3(Landroid/content/Context;Ljava/lang/String;Lcom/xiaomi/mirror/synergy/MiuiSynergySdk$RelayAppCallback;)V':[]}, {}),
  }),
 'captures/device-interconnect-20261108/hyperos4/com.xiaomi.mirror/MirrorOS4.apk': (
  '9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356', {
   'Lcom/xiaomi/mirror/sink/SinkView;': ({'getDeviceId()Ljava/lang/String;':[], 'getScreenId()I':[]}, {'p':'Lcom/xiaomi/mirror/l0;'}),
   'Lcom/xiaomi/mirror/sink/d;': ({}, {'g':'Lcom/xiaomi/mirror/l0;'}),
   'Lcom/xiaomi/mirror/k0;': ({'h()Ljava/lang/String;':[]}, {}),
  }),
}
evidence = []
for relative, (digest, wanted) in required.items():
    apk = root / relative
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == digest, 'OEM APK changed'
    found = set()
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.namelist():
            if not entry.startswith('classes') or not entry.endswith('.dex'):
                continue
            raw = archive.read(entry)
            for cls in DEX(raw).get_classes():
                name = cls.get_name()
                if name not in wanted:
                    continue
                methods, fields = wanted[name]
                actual = {m.get_name()+m.get_descriptor().replace(' ', ''): m for m in cls.get_methods()}
                actual_fields = {f.get_name(): f.get_descriptor() for f in cls.get_fields()}
                assert methods.keys() <= actual.keys(), (name, methods.keys()-actual.keys())
                assert all(actual_fields.get(k) == v for k, v in fields.items()), (name, fields, actual_fields)
                trace = {}
                for signature, tokens in methods.items():
                    lines = [i.get_name()+' '+i.get_output() for i in actual[signature].get_instructions()]
                    for token in tokens:
                        assert any(token in line for line in lines), (name, signature, token, lines)
                    if tokens:
                        trace[signature] = lines
                evidence.append(dict(apk=relative, apk_sha256=digest, dex=entry,
                    dex_sha256=hashlib.sha256(raw).hexdigest(), class_name=name,
                    methods=sorted(methods), fields=fields, instructions=trace))
                found.add(name)
    assert wanted.keys() <= found, wanted.keys()-found
out = root / 'build/screen-native-entry-validation/native-signatures.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(evidence, indent=2), encoding='utf-8')
print(f'PASS: {len(evidence)} OEM classes, {sum(len(e["methods"]) for e in evidence)} methods, '
      f'{sum(len(e["fields"]) for e in evidence)} fields; original selection/conversion/consent/peer call edges')
