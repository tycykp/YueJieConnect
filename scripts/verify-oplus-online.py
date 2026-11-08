"""Check the real APK contracts used by the native online/detail state repair."""
from pathlib import Path
from hashlib import sha256
import zipfile
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
root=Path(__file__).resolve().parents[1]
apk=root/'captures/device-interconnect-20261108/coloros17/com.oplus.linker/OPSynergy.apk'
assert sha256(apk.read_bytes()).hexdigest()=='dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b'
listener='Lcom/oplus/synergy/devicekit/callback/ISynergyDeviceChangeListener;'
required={
    'Lcom/oplus/synergy/devicekit/OpDeviceServiceClient;':{
        'registerDeviceChangeListener('+listener+')V','unRegisterDeviceChangeListener()V','unRegisterDeviceChangeListener('+listener+')V',
        'deviceDisconnect(Ljava/lang/String;IZLcom/oplus/synergy/devicekit/callback/ISynergyDeviceOperateCallback;)V'},
    'Lcom/oplus/synergy/devicekit/callback/ISynergyDeviceOperateCallback;':{'onResult(ILjava/lang/String;)V'},
    listener:{'onDeviceAdded(Ljava/util/List;)V','onDeviceInfoChanged(Ljava/util/List;)V','onDeviceRemoved(Ljava/util/List;)V'},
    'Lcom/oplus/linker/plugin/devicediscovery/thirdapi/ptc/PTCWrapper;':{
        'getConnectInfo(Lcom/oplus/pantaconnect/sdk/connectionservice/connection/DisplayDevice;Lcom/oplus/linker/connect/arch/ResultCallBack;)V'},
    'Lcom/oplus/linker/connect/arch/ResultCallBack;':{'onResult(Ljava/lang/Object;)V'},
}
checked=0;states=False
with zipfile.ZipFile(apk) as archive:
    for entry in (n for n in archive.namelist() if n.endswith('.dex')):
        for cls in DEX(archive.read(entry)).get_classes():
            if cls.get_name()=='Lcom/oplus/linker/synergy/util/PantaUtils;':
                fields={f.get_name():f.get_init_value().get_value() for f in cls.get_fields() if f.get_init_value() is not None}
                assert fields['DEVICE_STATE_CONNECTED']==2 and fields['DEVICE_STATE_DISCOVERED']==1 and fields['DEVICE_STATE_LOST']==0
                states=True
            if cls.get_name() in required:
                actual={m.get_name()+m.get_descriptor().replace(' ','') for m in cls.get_methods()}
                expected=required.pop(cls.get_name());assert expected<=actual,(cls.get_name(),expected-actual);checked+=len(expected)
        if not required and states:break
assert not required and states
print(f'PASS: {checked} original methods and native DeviceKit state constants.')
