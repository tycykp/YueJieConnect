"""Validate new native binding points in fixed OEM DEX, independently of JADX aliases."""
import hashlib
import json
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
root = Path(__file__).resolve().parents[1]
sink = 'Lcom/xiaomi/mirrorcontrol/MirrorControlSink;'
base = 'Lcom/oplus/cast/engine/impl/odmf/display/castdisplaymanager/'
required = {
 'captures/device-interconnect-20261108/hyperos4/com.xiaomi.mirror/MirrorOS4.apk': (
  '9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356', {
   sink: ({}, {'mCallback':'Lcom/xiaomi/mirrorcontrol/MirrorControlSink$SinkCallback;'}),
   'Lcom/xiaomi/mirrorcontrol/MirrorControl;': ({'releaseMirrorLogInfo(J)V':[]}, {}),
   'Lcom/xiaomi/mirror/sink/SinkView$d;': ({}, {'a':'Lcom/xiaomi/mirror/sink/SinkView;'}),
   'Lcom/xiaomi/mirror/sink/d$a;': ({}, {'a':'Lcom/xiaomi/mirror/sink/d;'}),
   'Lcom/xiaomi/mirror/sink/d;': ({}, {'a':'Ljava/lang/String;', 'b':'I', 'e':sink}),
   'Lcom/xiaomi/mirror/sink/SinkView;': ({
    'getDeviceId()Ljava/lang/String;':[], 'getScreenId()I':[], 'getIsShowAuth()Z':[], 'getRemoteDeviceKeyguardLock()Z':[],
    't1(I)V':['MirrorControlSink;-><init>', 'MirrorControlSink;->startMirror']}, {}),
  }),
 'captures/screen-input-20261108/coloros17/HeyCast.apk': (
  '2249716ffc2df561e8dec287d4e59bf02da4dafb19b18fb76c6981ff625cc3a0', {
   base+'CastDisplayManager;': ({
    'M()V':[],
    'y(Landroid/view/Surface;Lcom/oplus/cast/engine/impl/odmf/display/b;)I':['VirtualDisplay;->getDisplay','Display;->getDisplayId'],
    'F(Lz2/l;Lz2/l;ILjava/util/function/Consumer;)Z':['getVideoInputSurface'],
    'T(Landroid/util/Pair;Ljava/util/function/Consumer;Landroid/view/Surface;)V':['Consumer;->accept'],
    'k0()V':['Lcom/oplus/cast/engine/impl/odmf/display/h;->h']}, {'g':'Ljava/lang/String;', 'q':'Z'}),
   base+'PadCastDisplayManager;': ({'P(Landroid/view/Surface;Lz2/l;I)V':['->A']}, {}),
   'Lz2/l;': ({'c()I':[], 'b()I':[]}, {}),
   'Lcom/oplus/cast/engine/impl/odmf/a;': ({'J()V':['SYNERGY_PAD','PadCastDisplayManager;-><init>','CastDisplayManager;->M']},
     {'d':'Lcom/oplus/cast/service/sdk/DeviceInfo;','h':'Ljava/lang/String;','e':base+'CastDisplayManager;'}),
   'Lcom/oplus/cast/service/sdk/DeviceInfo;': ({'l()Ljava/lang/String;':[]}, {}),
   'Lj3/i;': ({'a()Lj3/i;':[], 'c(Ljava/lang/Runnable;)V':['Handler;->post']}, {}),
   'Lcom/oplus/cast/service/CastService;': ({
    'i0(Lcom/oplus/cast/service/sdk/DeviceInfo;Landroid/os/Bundle;ILjava/lang/String;Ljava/lang/String;)V':['Ld3/b;->q0']}, {}),
  }),
}
evidence=[]
for relative,(digest,wanted) in required.items():
 apk=root/relative
 assert hashlib.sha256(apk.read_bytes()).hexdigest()==digest, 'OEM APK changed'
 found=set()
 with zipfile.ZipFile(apk) as archive:
  for entry in archive.namelist():
   if not entry.startswith('classes') or not entry.endswith('.dex'): continue
   raw=archive.read(entry)
   for cls in DEX(raw).get_classes():
    name=cls.get_name()
    if name not in wanted: continue
    required_methods,required_fields=wanted[name]
    methods={m.get_name()+m.get_descriptor().replace(' ',''):m for m in cls.get_methods()}
    if name==sink: assert 'equals(Ljava/lang/Object;)Z' not in methods and 'hashCode()I' not in methods
    fields={f.get_name():f.get_descriptor() for f in cls.get_fields()}
    assert required_methods.keys()<=methods.keys(), (name,required_methods.keys()-methods.keys())
    assert all(fields.get(k)==v for k,v in required_fields.items()), (name,required_fields)
    trace={}
    for signature,tokens in required_methods.items():
     instructions=[i.get_name()+' '+i.get_output() for i in methods[signature].get_instructions()]
     for token in tokens: assert any(token in line for line in instructions), (name,signature,token)
     if tokens: trace[signature]=instructions
    evidence.append(dict(apk=relative,apk_sha256=digest,dex=entry,dex_sha256=hashlib.sha256(raw).hexdigest(),
                         class_name=name,methods=sorted(required_methods),fields=required_fields,instructions=trace))
    found.add(name)
 assert wanted.keys()<=found, wanted.keys()-found
out=root/'build/screen-native-binding-validation/native-signatures.json'
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(evidence,indent=2),encoding='utf-8')
print(f'PASS: {len(evidence)} OEM classes, {sum(len(e["methods"]) for e in evidence)} methods, {sum(len(e["fields"]) for e in evidence)} fields and native Surface/capture call edges')
