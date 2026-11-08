"""New receiver hooks and existing grant-capable OEM FileProvider roots, from fixed APK bytes."""
import hashlib
import json
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET
from androguard.core.axml import AXMLPrinter, ARSCParser
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
root = Path(__file__).resolve().parents[1]
S = 'Ljava/lang/String;'
required = {
    'hyperos4/com.xiaomi.mirror/MirrorOS4.apk': (
        '9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356',
        'com.xiaomi.mirror.fileprovider', 'res/xml/provider_paths.xml', {
            'Lcom/xiaomi/mirror/d0;': {
                'a(Lcom/xiaomi/mirror/message/DragMessage;J)V', 'onDragResult(Z)V',
                'onDelegatePermissionReleased(Ljava/util/List;)V'},
            'Lcom/xiaomi/mirror/provider/RemoteProvider;': {
                f'openFile(Landroid/net/Uri;{S}Landroid/os/CancellationSignal;)Landroid/os/ParcelFileDescriptor;'},
            'Lcom/xiaomi/mirror/connection/G;': {'J()Lcom/xiaomi/mirror/connection/G;', f'J0(I){S}',
                f't0({S})Lcom/xiaomi/mirror/l0;'},
            'LV3/V;': {'G()LV3/V;', f'M({S})Landroid/net/Uri;'},
        }),
    'coloros17/com.oplus.linker/OPSynergy.apk': (
        'dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b',
        'com.oplus.linker.devicemanager.fileprovider', 'res/xml/file_paths.xml', {}),
}
provider_type = 'Landroidx/core/content/FileProvider;'
provider_methods = {
    f'getUriForFile(Landroid/content/Context;{S}Ljava/io/File;{S})Landroid/net/Uri;',
    f'openFile(Landroid/net/Uri;{S})Landroid/os/ParcelFileDescriptor;',
    f'delete(Landroid/net/Uri;{S}[Ljava/lang/String;)I',
}
fields = {
    'Lcom/xiaomi/mirror/d0;': {'b': 'J', 'c': 'Landroid/content/ClipData;', 'e': 'I', 'g': 'I', 'j': 'Z'},
    'Lcom/xiaomi/mirror/message/DragMessage;': {
        'action': 'I', 'screenId': 'I', 'clipData': 'Lcom/xiaomi/mirror/message/ClipDataMessage;'},
    'Lcom/xiaomi/mirror/message/ClipDataMessage;': {'fileList': 'Ljava/util/List;'},
    'Lcom/xiaomi/mirror/message/ClipDataMessage$FileInfo;': {'url': S, 'name': S, 'size': 'J'},
}
android = '{http://schemas.android.com/apk/res/android}'
records = []
manifests = []
for relative, (expected_sha, authority, paths, wanted) in required.items():
    apk = root / 'captures/device-interconnect-20261108' / relative
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == expected_sha, 'Unexpected OEM APK'
    wanted = dict(wanted)
    wanted[provider_type] = provider_methods
    found = set()
    with zipfile.ZipFile(apk) as archive:
        manifest_raw = archive.read('AndroidManifest.xml')
        manifest = ET.fromstring(AXMLPrinter(manifest_raw).get_xml())
        providers = [p for p in manifest.findall('./application/provider') if p.get(android+'authorities') == authority]
        assert len(providers) == 1
        provider = providers[0]
        assert provider.get(android+'name') == 'androidx.core.content.FileProvider'
        assert provider.get(android+'exported') == 'false' and provider.get(android+'grantUriPermissions') == 'true'
        metadata = [m for m in provider.findall('meta-data') if m.get(android+'name') == 'android.support.FILE_PROVIDER_PATHS']
        assert len(metadata) == 1
        resource_id = int(metadata[0].get(android+'resource')[1:], 16)
        resources = ARSCParser(archive.read('resources.arsc'))
        assert resources.get_resource_xml_name(resource_id).endswith(':xml/'+Path(paths).stem)
        configs = resources.get_resolved_res_configs(resource_id)
        assert len(configs) == 1
        resource_entry = configs[0][1]
        paths_raw = archive.read(resource_entry)
        paths_xml = ET.fromstring(AXMLPrinter(paths_raw).get_xml())
        assert any(p.tag == 'cache-path' and p.get('path') == '.' for p in paths_xml)
        manifests.append(dict(apk=relative, apk_sha256=expected_sha, authority=authority,
            manifest_sha256=hashlib.sha256(manifest_raw).hexdigest(), provider_attributes=provider.attrib,
            paths=paths, resource_entry=resource_entry, paths_sha256=hashlib.sha256(paths_raw).hexdigest(),
            paths_xml=ET.tostring(paths_xml, encoding='unicode')))
        for entry in archive.namelist():
            if not entry.startswith('classes') or not entry.endswith('.dex'):
                continue
            data = archive.read(entry)
            for cls in DEX(data).get_classes():
                name = cls.get_name()
                selected_fields = fields.get(name, {}) if relative.startswith('hyperos4') else {}
                if name not in wanted and not selected_fields:
                    continue
                methods = {m.get_name()+m.get_descriptor().replace(' ', ''): m for m in cls.get_methods()}
                assert wanted.get(name, set()) <= methods.keys(), (name, wanted.get(name, set()) - methods.keys())
                actual_fields = {f.get_name(): f.get_descriptor() for f in cls.get_fields()}
                for field, typ in selected_fields.items():
                    assert actual_fields.get(field) == typ, (name, field)
                selected = sorted(wanted.get(name, set()))
                records.append(dict(apk=relative, apk_sha256=expected_sha, dex=entry,
                    dex_sha256=hashlib.sha256(data).hexdigest(), class_name=name, methods=selected, fields=selected_fields,
                    instructions={sig: [f'{i.get_name()} {i.get_output()}' for i in methods[sig].get_instructions()]
                                  for sig in selected}))
                found.add(name)
    assert wanted.keys() <= found, wanted.keys() - found
out = root / 'build/file-transfer-validation/native-receive.json'
out.write_text(json.dumps(dict(scope='Receiver signatures and real existing FileProvider roots; no runtime execution',
    script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), manifests=manifests, records=records),
    ensure_ascii=False, indent=2), encoding='utf-8')
print(f'PASS: {len(records)} receiver classes / {sum(len(r["methods"]) for r in records)} methods / {len(manifests)} existing OEM provider roots')
