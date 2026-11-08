"""Verify the new Xiaomi InCallUI hooks and inventory manifest identity for integration."""
from pathlib import Path
import hashlib
import json
import re
import zipfile
from loguru import logger
logger.remove()
from androguard.core.axml import AXMLPrinter
from androguard.core.dex import DEX

root = Path(__file__).resolve().parent.parent
output = root / 'build/calls-sms-validation'
inventory = json.loads((output / 'extra-apks.json').read_text(encoding='utf-8-sig'))
android = '{http://schemas.android.com/apk/res/android}'
records = []
expected = {'onBind': '(Landroid/content/Intent;)Landroid/os/IBinder;',
            'onUnbind': '(Landroid/content/Intent;)Z',
            'onCallAdded': '(Landroid/telecom/Call;)V',
            'onCallRemoved': '(Landroid/telecom/Call;)V'}
found = {}
for item in inventory:
    apk = Path(item['file'])
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == item['sha256']
    with zipfile.ZipFile(apk) as z:
        manifest = AXMLPrinter(z.read('AndroidManifest.xml')).get_xml_obj()
        services = []
        for service in manifest.findall('application/service'):
            name = service.get(android + 'name', '')
            if re.search(r'incall|relay|sms', name, re.I):
                services.append({'name': name, 'permission': service.get(android + 'permission'), 'exported': service.get(android + 'exported')})
        records.append({'serial': item['serial'], 'package': manifest.get('package'),
                        'versionCode': manifest.get(android + 'versionCode'), 'versionName': manifest.get(android + 'versionName'),
                        'sharedUserId': manifest.get(android + 'sharedUserId'), 'sha256': item['sha256'], 'services': services})
        if item['serial'] != 'b59f8656' or item['package'] != 'com.android.incallui':
            continue
        for name in z.namelist():
            if not re.fullmatch(r'classes\d*\.dex', name):
                continue
            dex = DEX(z.read(name))
            for cls in dex.get_classes():
                if cls.get_name() != 'Lcom/android/incallui/InCallServiceImpl;':
                    continue
                assert cls.get_superclassname() == 'Landroid/telecom/InCallService;'
                for method in cls.get_methods():
                    if method.get_name() in expected:
                        assert method.get_descriptor().replace(' ', '') == expected[method.get_name()]
                        found[method.get_name()] = {'descriptor': method.get_descriptor(), 'dex': name,
                                                   'instructions': [i.get_name() + ' ' + i.get_output() for i in method.get_instructions()]}
assert set(found) == set(expected), found.keys()
(output / 'incall-manifests-and-hooks.json').write_text(json.dumps({'packages': records, 'xiaomi_hooks': found}, ensure_ascii=False, indent=2), encoding='utf-8')
print('PASS: 4 Xiaomi InCallService hooks verified from original DEX; package identities recorded.')
for record in records:
    print(record['serial'], record['package'], 'sharedUserId=' + str(record['sharedUserId']))
