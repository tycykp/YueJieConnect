"""Audit a minimal exact phone-process proof service without launching or modifying the device."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import zipfile
from loguru import logger
logger.remove()
from androguard.core.dex import DEX
from androguard.core.axml import AXMLPrinter
root = Path(__file__).resolve().parent.parent
apk = root / 'captures/calls-sms-20261108/b59f8656/com.android.phone/base.apk'
service_name = 'com.android.phone.ModemSmartEngineService'
with zipfile.ZipFile(apk) as z:
    android = '{http://schemas.android.com/apk/res/android}'
    manifest = AXMLPrinter(z.read('AndroidManifest.xml')).get_xml_obj()
    application = manifest.find('application')
    service = next(s for s in application.findall('service') if s.get(android + 'name') == service_name)
    assert service.get(android + 'exported') == 'true'
    assert service.get(android + 'permission') is None and application.get(android + 'permission') is None
    assert service.get(android + 'process') == 'com.android.phone'
    raw = z.read('classes.dex')
    cls = next(c for c in DEX(raw).get_classes() if c.get_name() == 'Lcom/android/phone/ModemSmartEngineService;')
    assert cls.get_superclassname() == 'Landroid/app/Service;'
    methods = []
    for method in cls.get_methods():
        methods.append({'name': method.get_name(), 'descriptor': method.get_descriptor(),
                        'instructions': [f'{offset:04x}: {i.get_name()} {i.get_output()}' for offset, i in method.get_instructions_idx()]})
    assert {m['name'] for m in methods} == {'<init>', 'onBind'}
    binding = next(m for m in methods if m['name'] == 'onBind')
    assert binding['descriptor'] == '(Landroid/content/Intent;)Landroid/os/IBinder;'
    invokes = [line for line in binding['instructions'] if 'invoke-' in line]
    assert len(invokes) == 1 and 'Landroid/util/Log;->d' in invokes[0]
    result = {'command': 'python scripts/verify-telephony-process.py', 'utc': datetime.now(timezone.utc).isoformat(),
              'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'dex_sha256': hashlib.sha256(raw).hexdigest(),
              'version': manifest.get(android + 'versionName'), 'version_code': manifest.get(android + 'versionCode'),
              'shared_uid': manifest.get(android + 'sharedUserId'), 'service': service_name, 'process': 'com.android.phone',
              'exported': True, 'permission': None, 'methods': methods,
              'scope': 'Static service identity only; does not prove current device UID/PID or runtime bindability',
              'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    (root / 'build/calls-sms-validation/xiaomi-phone-proof-service.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print('PASS: exact exported com.android.phone service, only constructor and logging/null onBind')
