"""Original DEX descriptor check for the new projection + connection gates. No device actions."""
from pathlib import Path
import hashlib
import json
import zipfile
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
root=Path(__file__).resolve().parents[1]
base=root/'captures/device-interconnect-20261108'
L=lambda name:'L'+name.replace('.','/')+';'
S='Ljava/lang/String;'
C='com.oplus.pantaconnect.sdk.connectionservice.connection.'
D=L(C+'DisplayDevice')
info=L('com.xiaomi.continuity.netbus.DeviceInfo')
builder=L('com.xiaomi.continuity.netbus.DeviceInfo$Builder')
sdk_result=L('com.oplus.pantaconnect.sdk.SealedResult')
fut='Ljava/util/concurrent/CompletableFuture;'
required={
 L('com.miui.circulate.channel.SharedChannelImpl'):{'onInitialize()V','onRelease()V',f'onDeviceFound({S}{info})V',f'onDeviceLost({S}{info})V',f'shareChannel({S}{S})I',f'shareChannel({S}{S}I)I',f'shareChannel({S}I)I',f'send({S}{S}[B)I'},
 builder:{'<init>()V',f'setDeviceId({S}){builder}',f'setDeviceName({S}){builder}',f'setDeviceType(I){builder}',f'build(){info}'},
 L(C+'ConnectionServiceClientsImpl'):{f'startGlobalDeviceDiscovery({L(C+"DeviceDiscoveryOptions")}{L(C+"GlobalDeviceDiscoveryCallback")}){sdk_result}',f'stopGlobalDeviceDiscovery(){sdk_result}',f'getAvailableDevices(){fut}',f'requestConnection({D}{L(C+"GlobalDeviceConnectOptions")}{L(C+"GlobalDeviceConnectionCallback")})V'},
 L(C+'GlobalDeviceDiscoveryCallback'):{f'onDeviceFound({D})V',f'onDeviceLost({D})V'},
 L(C+'GlobalDeviceConnectionCallback'):{f'onConnectionFailure({D}Ljava/lang/Throwable;)V'},
 D:{f'getProtocolDeviceId(){S}',f'cancelConnection(){fut}',f'closeConnection({L(C+"GlobalDeviceConnectOptions")}){fut}',
    '<init>('+S+S+L('com.oplus.pantaconnect.sdk.DeviceType')+S+L(C+'DeviceState')+L(C+'AccountState')+'ZILjava/util/List;I'+S+L('com.oplus.pantaconnect.sdk.discovery.DiscoveryStrategy')+S+L('com.oplus.pantaconnect.sdk.connection.VLinkType')+S+')V'},
}
samples=[(base/'hyperos4/com.milink.service/base.apk','4d4ec36791dcc58b12efde494d24d0a5c8c219213433c1f986996c5277f95bd3',['classes2.dex','classes3.dex']),
         (base/'coloros17/com.oplus.linker/OPSynergy.apk','dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b',['classes2.dex'])]
evidence=[]
for path,sha,entries in samples:
    assert hashlib.sha256(path.read_bytes()).hexdigest()==sha
    with zipfile.ZipFile(path) as apk:
        for entry in entries:
            data=apk.read(entry)
            for cls in DEX(data).get_classes():
                name=cls.get_name()
                if name not in required:continue
                actual={m.get_name()+m.get_descriptor().replace(' ','') for m in cls.get_methods()}
                assert required[name]<=actual,(name,required[name]-actual)
                evidence.append(dict(apk_sha256=sha,dex=entry,dex_sha256=hashlib.sha256(data).hexdigest(),class_name=name,methods=sorted(required.pop(name))))
assert not required,required.keys()
(root/'build/gateway-hook-dex-evidence.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8')
print('Verified',len(evidence),'DEX classes and',sum(len(e['methods']) for e in evidence),'gateway descriptors against original APKs.')
