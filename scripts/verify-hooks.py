"""Verify selected hook descriptors against the ignored original APKs (androguard 4.1.4)."""
import hashlib
import json
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
root = Path(__file__).resolve().parents[1]
base = root / 'captures/device-interconnect-20261108'
targets = [
    (next((base / 'hyperos4/com.milink.service').glob('*.apk')), 'classes3.dex', [
        'Lcom/xiaomi/continuity/netbus/DeviceInfo;']),
    (next((base / 'hyperos4/com.milink.service').glob('*.apk')), 'classes2.dex', [
        'Lcom/miui/circulate/channel/SharedChannelImpl;']),
    (base / 'coloros17/com.oplus.linker/OPSynergy.apk', 'classes2.dex', [
        'Lcom/oplus/linker/connect/arch/discovery/DiscoveryImpl$startSearch$2;',
        'Lcom/oplus/pantaconnect/sdk/connectionservice/connection/DisplayDevice;']),
]
results = []
for apk, entry, classes in targets:
    with zipfile.ZipFile(apk) as z:
        data = z.read(entry)
    dex = DEX(data)
    for cls in dex.get_classes():
        if cls.get_name() in classes:
            methods = [m.get_name() + m.get_descriptor().replace(' ', '') for m in cls.get_methods()
                       if m.get_name().startswith(('onDevice', 'get', 'component'))]
            results.append(dict(apk=apk.name, apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                                dex=entry, dex_sha256=hashlib.sha256(data).hexdigest(),
                                class_name=cls.get_name(), methods=methods))
    del dex
out = root / 'build/hook-dex-evidence.json'
out.parent.mkdir(exist_ok=True)
out.write_text(json.dumps(results, indent=2), encoding='utf-8')
expected = json.loads((root / 'docs/hook-dex-evidence.json').read_text(encoding='utf-8'))
assert results == expected, 'APK hash or DEX signatures differ from the reviewed baseline'
assert len(results) == 4, 'Missing hook/model classes'
print('Verified both APK hashes, 4 DEX classes, 5 discovery callbacks and model getter descriptors.')
