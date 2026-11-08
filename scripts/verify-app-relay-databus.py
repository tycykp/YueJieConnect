"""Pin the real phone/pad application-relay DataBus ingress/egress for the shared owner."""
import hashlib
import json
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
ROOT=Path(__file__).resolve().parents[1]
APK=ROOT/'captures/device-interconnect-20261108/coloros17/com.oplus.linker/OPSynergy.apk'
PIN='dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b'
T='Lcom/oplus/pantaconnect/sdk/extensions/Topic;'
P='Lcom/oplus/pantaconnect/sdk/extensions/DataBusPublisher;'
S='Lcom/oplus/pantaconnect/sdk/extensions/DataBusSubscriber;'
STR='Ljava/lang/String;'
F='Ljava/util/concurrent/CompletableFuture;'
R='Lcom/oplus/pantaconnect/sdk/SealedResult;'
required={
 'Lcom/oplus/synergy/linkmanager/LinkManager;':{
  f'lambda$createPublisher$13({T}ZLjava/util/List;{STR})V',f'lambda$createPublisher$14({P})V',
  f'lambda$createSubscriber$16({T}{T}{STR}{STR}{STR})V',f'lambda$createSubscriber$17({T}{S})V',
  'deInitONet()V','initONet()V',
  'onDeviceChange(Lcom/oplus/pantaconnect/sdk/connectionservice/connection/DisplayDevice;Lcom/oplus/pantaconnect/sdk/connectionservice/connection/DisplayDevice;)V'},
 'Lcom/oplus/pantaconnect/sdk/extensions/internal/DataBusClientsImpl;':{
  f'createPublisher({T}){R}',f'createSubscriber({T}){R}',f'publish({T}{STR}){R}',
  f'observeSubscriber({T}Lw11;)V',f'subscribe({T}Lw11;)V'},
 'Lcom/oplus/pantaconnect/sdk/extensions/PublishEventCallback;':{f'onSubscriberAvailable({T}ZLjava/util/List;{STR})V'},
 'Lcom/oplus/pantaconnect/sdk/extensions/MessageExtraCallback;':{f'onReceive({T}{STR}{STR}{STR})V'},
 'Lcom/oplus/pantaconnect/sdk/extensions/DataBusPublisher;':{f'create(Lcom/oplus/pantaconnect/sdk/extensions/PublishEventCallback;){F}',f'publish({STR}){F}',f'release(){F}'},
 'Lcom/oplus/pantaconnect/sdk/extensions/DataBusSubscriber;':{f'subscribe(Lcom/oplus/pantaconnect/sdk/extensions/MessageExtraCallback;){F}',f'release(){F}'},
 'Lcom/oplus/pantaconnect/sdk/InternalDataBusPayload;':{f'getPubDeviceId(){STR}',f'getMessage(){STR}',f'getExtra(){STR}'},
 'Lcom/oplus/pantaconnect/sdk/InternalDataBusEvent;':{f'getDeviceIdList(){STR}',f'getParam(){STR}',f'getEvent(){STR}'},
}
assert hashlib.sha256(APK.read_bytes()).hexdigest()==PIN
evidence=[]
with zipfile.ZipFile(APK) as archive:
 for entry in sorted(n for n in archive.namelist() if n.endswith('.dex')):
  if not required: break
  data=archive.read(entry)
  for cls in DEX(data).get_classes():
   name=cls.get_name()
   if name not in required: continue
   specs=required.pop(name)
   methods={m.get_name()+m.get_descriptor().replace(' ',''):m for m in cls.get_methods()}
   assert specs <= methods.keys(),(name,specs-methods.keys())
   evidence.append(dict(class_name=name,dex=entry,dex_sha256=hashlib.sha256(data).hexdigest(),
    methods={sig:[f'{i.get_name()} {i.get_output()}' for i in methods[sig].get_instructions()] for sig in sorted(specs)}))
assert not required, required
out=ROOT/'build/app-relay-validation/databus-native-evidence.json'
out.write_text(json.dumps(dict(result='passed',apk_sha256=PIN,script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),classes=evidence),ensure_ascii=False,indent=2),encoding='utf-8')
print(f'PASS: {len(evidence)} DataBus native classes and callback/peer-ID signatures. Observation only; no fabricated readiness.')
