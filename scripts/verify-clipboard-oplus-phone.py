"""Pin the new ColorOS phone/tablet clipboard source to original DEX, without device access."""
import hashlib
import argparse
import json
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
parser = argparse.ArgumentParser()
parser.add_argument('--lease-only', action='store_true')
args = parser.parse_args()
ROOT = Path(__file__).resolve().parents[1]
apk = ROOT / 'captures/device-interconnect-20261108/coloros17/com.oplus.linker/OPSynergy.apk'
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
assert digest == 'dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b'
required = {
    'Lcom/oplus/linker/synergy/wisetransfer/ICDFSceneManager;': {
        'dealClipBoardInfo()V', 'execute(ILjava/lang/Object;Ljava/lang/Object;)V'},
    'Lcom/oplus/linker/synergy/bus/scene/manager/AbstractSceneManager;': {'getMContext()Landroid/content/Context;'},
    'Lcom/oplus/linker/synergy/common/utils/ConnectPCUtil;': {'getClipboardSwitchState(Landroid/content/Context;)Z'},
    'Lcom/oplus/linker/synergy/wisetransfer/clipboard/scene/ClipBoardDataBusScene;': {
        'publishClipboardData(Lcom/oplus/linker/synergy/wisetransfer/clipboard/data/ClipInfoBean;)V'},
    'Lcom/oplus/linker/synergy/wisetransfer/clipboard/scene/ClipBoardDataBusScene$Companion;': {'isClipDataRepeat()Z'},
}
if args.lease_only:
    required = {name: methods for name, methods in required.items() if '/clipboard/scene/ClipBoardDataBusScene' in name}
evidence = []
with zipfile.ZipFile(apk) as archive:
    for entry in sorted(n for n in archive.namelist() if n.endswith('.dex')):
        content = archive.read(entry)
        for cls in DEX(content).get_classes():
            name = cls.get_name()
            if name not in required:
                continue
            methods = {m.get_name() + m.get_descriptor().replace(' ', ''): m for m in cls.get_methods()}
            assert required[name] <= methods.keys(), name
            fields = [f.get_name() + ':' + f.get_descriptor() for f in cls.get_fields()]
            if name == 'Lcom/oplus/linker/synergy/wisetransfer/clipboard/scene/ClipBoardDataBusScene;':
                assert 'Companion:Lcom/oplus/linker/synergy/wisetransfer/clipboard/scene/ClipBoardDataBusScene$Companion;' in fields
            instructions = {sig: [i.get_name() + ' ' + i.get_output() for i in methods[sig].get_instructions()]
                            for sig in required[name]}
            evidence.append(dict(name=name, dex=entry, dex_sha256=hashlib.sha256(content).hexdigest(), fields=fields, instructions=instructions))
assert len(evidence) == len(required)
if not args.lease_only:
    execute = next(e for e in evidence if e['name'].endswith('/ICDFSceneManager;'))['instructions']['execute(ILjava/lang/Object;Ljava/lang/Object;)V']
    repeat_index = next(i for i, line in enumerate(execute) if 'isClipDataRepeat()Z' in line and 'invoke' in line)
    dispatch_index = next(i for i, line in enumerate(execute) if '->dealClipBoardInfo()V' in line and 'invoke' in line)
    assert repeat_index < dispatch_index
    assert any('if-' in line for line in execute[repeat_index:dispatch_index])
out = ROOT / ('build/clipboard-validation/oplus-source-lease-dex.json' if args.lease_only else 'build/clipboard-validation/oplus-phone-dex.json')
out.write_text(json.dumps(dict(apk_sha256=digest, classes=evidence,
    implementation_sha256=hashlib.sha256((ROOT / 'android/module/src/main/java/io/github/yuejie/connect/OplusClipboardPhoneSource.java').read_bytes()).hexdigest(),
    scope='Native repeat-state singleton and methods; not device proof' if args.lease_only else 'Native execute type27 -> repeat check -> dealClipBoardInfo, native phone/tablet clipboard setting; not device proof'), indent=2), encoding='utf-8')
print(f'PASS: {len(required)} classes / {sum(len(v) for v in required.values())} descriptors and native fields; {out}')
