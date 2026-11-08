"""New native URI provider methods, policies and actual processes, from fixed OEM APK bytes."""
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET
from androguard.core.axml import AXMLPrinter
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
ROOT = Path(__file__).resolve().parents[1]
S = 'Ljava/lang/String;'
U = 'Landroid/net/Uri;'
C = 'Landroid/os/CancellationSignal;'
P = 'Landroid/os/ParcelFileDescriptor;'
Q5 = f'query({U}[{S}{S}[{S}{S})Landroid/database/Cursor;'
SAMPLES = [
    ('hyperos4/com.xiaomi.mirror/MirrorOS4.apk', '9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356',
     'com.xiaomi.mirror.remoteprovider', 'com.xiaomi.mirror.provider.RemoteProvider', 'com.xiaomi.mirror', True,
     {f'openFile({U}{S}{C}){P}', f'f({S})Z', f'getType({U}){S}', Q5,
      f'query({U}[{S}{S}[{S}{S}{C})Landroid/database/Cursor;'}),
    ('coloros17/com.oplus.linker/OPSynergy.apk', 'dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b',
     'com.oplus.linker.clipboard', 'com.oplus.linker.synergy.wisetransfer.clipboard.provider.ClipboardProvider', 'com.oplus.linker', False,
     {f'openFile({U}{S}){P}', f'openFile({U}{S}{C}){P}', f'getType({U}){S}'}),
]


def main():
    records = []
    a = '{http://schemas.android.com/apk/res/android}'
    for relative, sha, authority, class_name, process, exported, required in SAMPLES:
        apk = ROOT / 'captures/device-interconnect-20261108' / relative
        assert hashlib.sha256(apk.read_bytes()).hexdigest() == sha
        with zipfile.ZipFile(apk) as archive:
            raw = archive.read('AndroidManifest.xml')
            manifest = ET.fromstring(AXMLPrinter(raw).get_xml())
            app = manifest.find('application')
            providers = [p for p in app.findall('provider') if p.get(a+'authorities') == authority]
            assert len(providers) == 1
            provider = providers[0]
            actual_process = provider.get(a+'process') or app.get(a+'process') or manifest.get('package')
            assert provider.get(a+'name') == class_name and provider.get(a+'grantUriPermissions') == 'true'
            assert (provider.get(a+'exported') == 'true') == exported and actual_process == process
            found = {}
            wanted = 'L'+class_name.replace('.', '/')+';'
            for entry in archive.namelist():
                if not entry.startswith('classes') or not entry.endswith('.dex'):
                    continue
                data = archive.read(entry)
                for cls in DEX(data).get_classes():
                    if cls.get_name() != wanted and cls.get_name() != 'Landroidx/core/content/FileProvider;':
                        continue
                    methods = {m.get_name()+m.get_descriptor().replace(' ', ''): m for m in cls.get_methods()}
                    if cls.get_name() == wanted:
                        assert required <= methods.keys(), required - methods.keys()
                        found['provider'] = dict(dex=entry, dex_sha256=hashlib.sha256(data).hexdigest(), methods=sorted(required),
                            policy_instructions=[i.get_name()+' '+i.get_output() for i in methods.get(f'f({S})Z', []).get_instructions()] if f'f({S})Z' in methods else [])
                    else:
                        assert Q5 in methods
                        found['inherited_query'] = dict(dex=entry, method=Q5)
            assert 'provider' in found
            records.append(dict(apk=relative, apk_sha256=sha, manifest_sha256=hashlib.sha256(raw).hexdigest(),
                authority=authority, actual_process=actual_process, provider_attributes=provider.attrib, **found))
    out = ROOT / 'build/clipboard-validation/uri-native-providers.json'
    out.write_text(json.dumps(dict(command='python scripts/verify-clipboard-uri-providers.py', result='passed', providers=records,
        source_sha256={str(Path(__file__).relative_to(ROOT)): hashlib.sha256(Path(__file__).read_bytes()).hexdigest()},
        device_operations=False), indent=2), encoding='utf-8')
    print('PASS fixed OEM APKs: 2 native URI providers, 8 declared methods, actual default processes and inherited query')


if __name__ == '__main__':
    main()
