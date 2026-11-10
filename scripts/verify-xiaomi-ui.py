"""Verify every Xiaomi Circulate UI adapter reflection target against the pinned original DEX."""
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
L=lambda s:'L'+s.replace('.','/')+';'
S='Ljava/lang/String;'
D=L('com.miui.circulate.api.service.CirculateDeviceInfo')
V=L('com.miui.circulate.api.service.CirculateServiceInfo')
E=L('com.miui.circulate.api.bean.ExtraBundle')
B=L('com.miui.circulate.api.bean.ExtraBundle$b')
base='com.miui.circulate.world.sticker.blecard.'
required={
 L("dg.d"):{"T("+S+")V","f0("+S+")V"},
 L("com.miui.circulate.api.protocol.synergy.SynergyController"):{"connectCellularTether("+S+")V","disconnectCellularTether("+S+")V"},
 L('com.miui.circulate.api.protocol.miuiplus.MiuiPlusClient'):{
 'startDiscovery(Ldd/a;Ljava/util/concurrent/Executor;)V','stopDiscovery(Ldd/a;Z)V','release()V','unRegisterListener()V',
 'circulateService(Ljava/util/List;Ljava/util/List;Lcom/miui/circulate/api/bean/CirculateParam;)V'},
 L('com.miui.circulate.api.protocol.impl.b'):{'isAvailable()Z'},
 L('com.miui.circulate.api.bean.CirculateParam'):set(),
 L('com.miui.circulate.api.protocol.impl.a'):{f'circulateResult(Lsc/a;II[{D})V'},
 L('sc.a'):{f'e(I{D}{V})V',f'f(I{D}{V})V'},
 D:{'<init>()V'},V:{f'build(I){V}'},
 B:{'<init>()V',f'g({S}{S}){B}',f'c({S}Z){B}',f'e({S}I){B}',f'a(){E}'},
 L(base+'PhoneCard'):{name+'()V' for name in ('H2','J2','J3','M3','O3','P3')},
 L('com.miui.circulate.world.sticker.MainCardView'):{'p(Z)V',f'k({D}{S}{S}Lpf/a;Lcom/miui/circulate/world/base/h;Lgf/g;)V'},
 L(base+'BleView'):{f'getMInfo(){D}','getCardH()I','setCardH(I)V'}|{name+'()V' for name in ('I3','K3','L3','N3','w1')},
 L('com.miui.circulate.world.view.BaseAudioVolumeBarView'):{
 '<init>(Landroid/content/Context;)V','getVolumeRowSlider()Lcom/miui/circulate/world/view/MiPlayVolumeBar;',
 'getVolumeNum()Landroid/widget/TextView;','getVolumeBarTitleContainer()Landroid/widget/LinearLayout;'},
 L('miuix.appcompat.app.u$a'):{'<init>(Landroid/content/Context;)V',
 'm([Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Lmiuix/appcompat/app/u$a;',
 'a()Lmiuix/appcompat/app/u;'}
}
required[L('com.miui.circulate.world.R$id')]=set()
fields={
 L('com.miui.circulate.api.bean.CirculateParam'):{f'circulateServiceInfo:{V}'},
 L('com.miui.circulate.api.protocol.miuiplus.MiuiPlusClient'):{'mDiscovery:Ljava/util/concurrent/atomic/AtomicBoolean;'},
 L('com.miui.circulate.world.R$id'):{name+':I' for name in ('use_home_screen','ringing_view','ringing_view_bottom','use_cam','use_cam_top','use_cellular','use_cellular_top','cam_in_use','in_use_top','in_use','remote_control','remote_control_top','device_synergy','container')},
 D:{f'id:{S}',f'idHash:{S}',f'devicesName:{S}',f'devicesType:{S}',f'deviceProperties:{E}','circulateServices:Ljava/util/Set;'},
 V:{f'deviceId:{S}','connectState:I','protocolType:I'},
 L('com.miui.circulate.api.protocol.impl.b'):{'mCallback:Lsc/a;'}
}
evidence=[]
with zipfile.ZipFile(apk) as archive:
 for entry in (name for name in archive.namelist() if name.endswith('.dex')):
  data=archive.read(entry)
  for cls in DEX(data).get_classes():
   name=cls.get_name()
   if name not in required:continue
   methods={m.get_name()+m.get_descriptor().replace(' ','') for m in cls.get_methods()}
   assert required[name]<=methods,(name,required[name]-methods)
   actual_fields={f.get_name()+':'+f.get_descriptor() for f in cls.get_fields()}
   expected_fields=fields.pop(name,set())
   assert expected_fields<=actual_fields,(name,expected_fields-actual_fields)
   evidence.append({'apk_sha256':sha,'dex':entry,'dex_sha256':hashlib.sha256(data).hexdigest(),
       'class':name,'methods':sorted(required.pop(name)),'fields':sorted(expected_fields)})
assert not required and not fields,(required,fields)
(root/'adapters/hyperos4/dex-ui-evidence.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8')
print('Verified',len(evidence),'classes,',sum(len(c['methods']) for c in evidence),'UI methods and',sum(len(c['fields']) for c in evidence),'fields.')
