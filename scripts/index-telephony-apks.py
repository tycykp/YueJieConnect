"""Index original system APK classes for native telephony/SMS entry points, no device access."""
from pathlib import Path
import json
import re
import zipfile
from loguru import logger
logger.remove()
from androguard.core.dex import DEX

root = Path(__file__).resolve().parent.parent
output = root / 'build/calls-sms-validation'
inventory = json.loads((output / 'extra-apks.json').read_text(encoding='utf-8-sig'))
records = []
for item in inventory:
    matches = []
    with zipfile.ZipFile(item['file']) as z:
        for name in z.namelist():
            if not re.fullmatch(r'classes\d*\.dex', name):
                continue
            dex = DEX(z.read(name))
            for cls in dex.get_classes():
                class_name = cls.get_name()
                methods = []
                for method in cls.get_methods():
                    if re.search(r'mirror|synergy|call.?relay|update_sms_status|message-external', method.get_name(), re.I):
                        methods.append(method.get_name() + method.get_descriptor())
                if (re.search(r'mirror|synergy|relay|remote.?call|incallservice', class_name, re.I)
                        or methods) and not class_name.startswith(('Landroidx/', 'Lkotlin', 'Lcom/google/')):
                    matches.append({'class': class_name, 'super': cls.get_superclassname(), 'methods': methods, 'dex': name})
    records.append({'serial': item['serial'], 'package': item['package'], 'sha256': item['sha256'], 'matches': matches})
(output / 'extra-apk-class-index.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
for record in records:
    print(record['serial'], record['package'], len(record['matches']), 'matching classes')
