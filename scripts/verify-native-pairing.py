"""Verify the original ColorOS pairing Activity, callbacks and IPC against pinned DEX."""
import hashlib
import json
from pathlib import Path
import zipfile
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
root = Path(__file__).resolve().parents[1]
apk = root / 'captures/device-interconnect-20261108/coloros17/analysis/dependencies/com.heytap.accessory/AccessoryFramework.apk'
expected = '538e5665ea6c709827063cc8b2f2dc86c82d432252c41ca4606526be7497ec78'
assert hashlib.sha256(apk.read_bytes()).hexdigest() == expected, 'AccessoryFramework sample changed'
base = 'Lcom/oplus/pantaconnect/'
data = base + 'model/ConnectViewData;'
state = base + 'appcore/connect/constants/ConnectState;'
command = base + 'common/DialogSendCommand;'
required = {
    data: {'<init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;ZZ)V', 'writeToParcel(Landroid/os/Parcel;I)V'},
    base + 'appcore/connect/ui/DeviceConnectDialogActivity;': {'l(' + state + ')V', 'onCreate(Landroid/os/Bundle;)V'},
    base + 'appcore/utils/t;': {'a(Landroid/content/Context;' + data + command + ')V'},
    base + 'appcore/dialog/IInnerIpcInterface;': {'request(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;'},
}
evidence = []
with zipfile.ZipFile(apk) as archive:
    for name in ('classes3.dex', 'classes4.dex', 'classes2.dex', 'classes.dex'):
        if not required:
            break
        for cls in DEX(archive.read(name)).get_classes():
            wanted = required.get(cls.get_name())
            if wanted is None:
                continue
            actual = {m.get_name() + m.get_descriptor().replace(' ', '') for m in cls.get_methods()}
            assert wanted <= actual, (cls.get_name(), wanted - actual)
            evidence.append({'class': cls.get_name(), 'dex': name, 'methods': sorted(wanted)})
            required.pop(cls.get_name())
assert not required, required
linker = root / 'captures/device-interconnect-20261108/coloros17/com.oplus.linker/OPSynergy.apk'
assert hashlib.sha256(linker.read_bytes()).hexdigest() == 'dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b'
with zipfile.ZipFile(linker) as archive:
    classes = DEX(archive.read('classes2.dex')).get_classes()
    cls = next(c for c in classes
               if c.get_name() == 'Lcom/oplus/linker/mac/client/data/source/remote/MacRemoteDataSource;')
    methods = {m.get_name() + m.get_descriptor().replace(' ', '') for m in cls.get_methods()}
    signature = 'isWlanType(Lcom/oplus/pantaconnect/sdk/connectionservice/connection/DisplayDevice;)Z'
    assert signature in methods, 'Native outgoing connection result route changed'
    evidence.append({'class': cls.get_name(), 'dex': 'linker/classes2.dex', 'methods': [signature]})
    name = 'Lcom/oplus/linker/plugin/devicediscovery/ui/DeviceDiscoveryPreferenceFragment;'
    cls = next((c for c in classes if c.get_name() == name), None)
    if cls is None:
        cls = next(c for c in DEX(archive.read('classes6.dex')).get_classes() if c.get_name() == name)
    signature = 'isDuplicateDevice(Lcom/oplus/linker/plugin/devicediscovery/bean/DeviceBean;Lcom/oplus/synergy/devicekit/bean/SynergyDeviceInfo;)Z'
    assert signature in {m.get_name() + m.get_descriptor().replace(' ', '') for m in cls.get_methods()}
    evidence.append({'class': name, 'methods': [signature]})
out = root / 'build/native-pairing-device/oem-signatures.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps({'apk_sha256': expected, 'classes': evidence}, indent=2), encoding='utf-8')
print('PASS: original pairing Activity, native acceptance callback, Parcelable and Binder signatures.')
