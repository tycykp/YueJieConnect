"""Validate only new logical/proof signatures against the original, pinned OEM APKs."""
from pathlib import Path
from hashlib import sha256
import json,zipfile,sys
from androguard.core.apk import APK
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
root=Path(__file__).resolve().parents[1]
C='Lcom/oplus/pantaconnect/sdk/connectionservice/connection/'
T='Lcom/oplus/pantaconnect/sdk/connection/'
D=C+'DisplayDevice;'
F='Ljava/util/concurrent/CompletableFuture;'
S='Ljava/lang/String;'
required={
 C+'GlobalDeviceConnectOptions;':{'getConnectionType()'+T+'ConnectionType;','getChannelType()'+C+'ChannelType;'},
 C+'GlobalDeviceConnectionInfo;':{'<init>('+T+'ConnectionType;'+C+'ChannelType;'+S*3+T+'VLinkType;)V'},
 C+'GlobalDeviceConnectionCallback;':{'onConnectionSuccess('+D+C+'GlobalDeviceConnectionInfo;)V'},
 C+'DisplayDeviceChangedCallback;':{'onChanged('+D+D+')V'},
 C+'ConnectionServiceImpl;':{'getConnectionList('+S+T+'ConnectionType;)'+F,'registerDisplayDeviceStateListener('+C+'DisplayDeviceChangedCallback;)V','unregisterDisplayDeviceStateListener()'+F},
}
routes_only='--routes-only' in sys.argv
if routes_only:
 required={
  C+'GlobalDeviceConnectOptions;':{'copy('+T+'ConnectionType;'+C+'ChannelType;'+C+'OptionExtensionArgs;'+C+'DirectDecision;)'+C+'GlobalDeviceConnectOptions;','getExtra()'+C+'OptionExtensionArgs;','getDirectDecision()'+C+'DirectDecision;'},
  'Lcom/oplus/linker/synergy/wiseconnect/senseless/SenselessConnectionUtil;':{'deviceConnectOptions(Ljava/lang/Boolean;'+D+')'+C+'GlobalDeviceConnectOptions;'},
  'Lcom/oplus/linker/synergy/ui/fragment/DeviceConnectPreferenceFragment;':{'isLanDevice('+D+')Z','isWlanType(Lcom/oplus/pantaconnect/sdk/DeviceType;)Z'},
 }
oplus=root/'captures/device-interconnect-20261108/coloros17/com.oplus.linker/OPSynergy.apk'
mirror=root/'captures/device-interconnect-20261108/hyperos4/com.xiaomi.mirror/MirrorOS4.apk'
assert sha256(oplus.read_bytes()).hexdigest()=='dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b'
assert sha256(mirror.read_bytes()).hexdigest()=='9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356'
records=[]
cases=[(oplus,required,['classes2.dex'])]
if not routes_only:cases.append((mirror,{'Lcom/xiaomi/mirror/SynergyService;':{'onBind(Landroid/content/Intent;)Landroid/os/IBinder;'}},['classes.dex']))
for path,wanted,entries in cases:
 with zipfile.ZipFile(path) as z:
  for entry in entries:
   data=z.read(entry);dex=DEX(data)
   for c in dex.get_classes():
    if c.get_name() not in wanted:continue
    actual={m.get_name()+m.get_descriptor().replace(' ',''):m for m in c.get_methods()}
    methods=wanted.pop(c.get_name());assert methods<=actual.keys(),(c.get_name(),methods-actual.keys())
    if c.get_name().endswith('/DeviceConnectPreferenceFragment;'):assert any(f.get_name()=='mONetDevice' and f.get_descriptor()==D for f in c.get_fields())
    records.append({'apk':path.name,'apk_sha256':sha256(path.read_bytes()).hexdigest(),'dex_sha256':sha256(data).hexdigest(),'class':c.get_name(),'methods':sorted(methods)})
 assert not wanted,wanted
service_record={}
if not routes_only:
 a=APK(str(mirror));ns='{http://schemas.android.com/apk/res/android}'
 service=[s for s in a.get_android_manifest_xml().findall('.//service') if s.get(ns+'name')=='com.xiaomi.mirror.SynergyService'][0]
 assert service.get(ns+'exported')=='true' and not service.get(ns+'permission') and not service.get(ns+'process')
 service_record=dict(service.attrib)
out=root/('build/native-pairing-validation/logical-routes-dex.json' if routes_only else 'build/native-pairing-validation/logical-dex.json')
out.write_text(json.dumps({'classes':records,'mirror_service':service_record},indent=2),encoding='utf-8')
print(f'Validated {len(records)} original classes and {sum(len(r["methods"]) for r in records)} exact signatures.')
