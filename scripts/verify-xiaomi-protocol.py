"""Pin Xiaomi adapter signatures and export raw DEX business instructions. No device changes."""
import hashlib
import json
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
root=Path(__file__).resolve().parents[1]
apk=root/'captures/device-interconnect-20261108/hyperos4/com.milink.service/base.apk'
sha='4d4ec36791dcc58b12efde494d24d0a5c8c219213433c1f986996c5277f95bd3'
assert hashlib.sha256(apk.read_bytes()).hexdigest()==sha
S='Ljava/lang/String;'
D='Lcom/xiaomi/continuity/netbus/DeviceInfo;'
B='Lcom/xiaomi/continuity/netbus/DeviceInfo$Builder;'
required={
 'Lcom/miui/circulate/channel/SharedChannelImpl;':{
  'onInitialize()V','onRelease()V',f'removeCaller({S})V',f'onDeviceFound({S}{D})V',f'onDeviceLost({S}{D})V',
  f'shareChannel({S}{S})I',f'shareChannel({S}{S}I)I',f'shareChannel({S}I)I',f'send({S}{S}[B)I'},
 B:{'<init>()V',f'setDeviceId({S}){B}',f'setDeviceName({S}){B}',f'setDeviceType(I){B}',f'build(){D}'},
 'Lcom/milink/deviceprofile/service/DeviceProfileManagerService;':{'c([B)[B'},
 'Lcom/milink/deviceprofile/service/DeviceProfileManagerProxy;':{f'i({S}Ljava/lang/Integer;[B)V','k(Lcom/milink/deviceprofile/service/d;)V'},
 'Lcom/milink/deviceprofile/service/SharedChannelReceiver;':{'onReceive(Landroid/content/Context;Landroid/content/Intent;)V'},
 'Lcom/milink/deviceprofile/api/DeviceProfileManager;':{'<init>()V','init(Landroid/content/Context;)V',f'syncDeviceProfile({S}ILcom/milink/deviceprofile/api/DeviceProfileManager$IProfileEventCallback;)V','unInit()V'},
 'Lcom/milink/deviceprofile/api/DeviceProfileManager$IProfileEventCallback;':{'onResponse(Lcom/milink/deviceprofile/api/models/Profile;)V','onSyncFail()V'},
 'Lcom/milink/deviceprofile/api/models/Profile;':{'getDeviceInfo()Lcom/milink/deviceprofile/api/models/DeviceInfo;'},
 'Lcom/milink/deviceprofile/api/models/DeviceInfo;':{'getBattery()Ljava/lang/Integer;','getChargingState()Ljava/lang/Integer;'},
}
evidence=[]
with zipfile.ZipFile(apk) as archive:
 for entry in ('classes2.dex','classes3.dex'):
  data=archive.read(entry)
  for cls in DEX(data).get_classes():
   name=cls.get_name()
   if name not in required:continue
   methods={m.get_name()+m.get_descriptor().replace(' ',''):m for m in cls.get_methods()}
   assert required[name]<=methods.keys(),(name,required[name]-methods.keys())
   if 'SharedChannelImpl;' in name:
    assert any(f.get_name()=='context' and f.get_descriptor()=='Landroid/content/Context;' for f in cls.get_fields())
   selected=sorted(required.pop(name))
   evidence.append(dict(class_name=name,apk_sha256=sha,dex=entry,dex_sha256=hashlib.sha256(data).hexdigest(),methods=selected,
    instructions={sig:[f'{ins.get_name()} {ins.get_output()}' for ins in methods[sig].get_instructions()]
                  for sig in selected if '/deviceprofile/' in name}))
assert not required,required
out=root/'adapters/hyperos4/dex-protocol-evidence.json'
out.write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
print('Verified Xiaomi APK hash,',len(evidence),'classes,',sum(len(c['methods']) for c in evidence),'methods and context field; exported original business instructions.')
