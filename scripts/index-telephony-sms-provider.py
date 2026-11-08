"""Inspect only saved SMS provider classes/bytecode, never a user's SMS database."""
from pathlib import Path
import hashlib
import json
import re
import zipfile
from loguru import logger
logger.remove()
from androguard.core.dex import DEX
root = Path(__file__).resolve().parent.parent
apk = root / 'captures/calls-sms-20261108/3B161Q00YN200000/com.android.mms/base.apk'
result = {'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'classes': [], 'method_mentions': [], 'provider_call': []}
with zipfile.ZipFile(apk) as z:
    for name in z.namelist():
        if not re.fullmatch(r'classes\d*\.dex', name):
            continue
        raw = z.read(name)
        dex = DEX(raw)
        for cls in dex.get_classes():
            if '/communicationshare/' in cls.get_name():
                result['classes'].append({'class': cls.get_name(), 'methods': [m.get_name() + m.get_descriptor() for m in cls.get_methods()]})
            for method in cls.get_methods():
                if not method.get_code():
                    continue
                is_provider = cls.get_name() == 'Lcom/oplus/mms/datamodel/MessageExternalProvider;' and method.get_name() == 'call'
                instructions = []
                for offset, instruction in method.get_instructions_idx():
                    if is_provider or instruction.get_name().startswith('const-string'):
                        instructions.append(f'{offset:04x}: {instruction.get_name()} {instruction.get_output()}')
                if is_provider:
                    result['provider_call'].append({'descriptor': method.get_descriptor(), 'instructions': instructions, 'dex_sha256': hashlib.sha256(raw).hexdigest()})
                if any('update_sms_status' in line or 'nearcomm.sms.' in line for line in instructions):
                    result['method_mentions'].append({'class': cls.get_name(), 'method': method.get_name(), 'strings': instructions})
output = root / 'build/calls-sms-validation/oplus-sms-provider.json'
output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print('Communicationshare classes:', len(result['classes']), 'nearcomm/status string references:', len(result['method_mentions']))
for record in result['classes']:
    if '$' not in record['class']:
        print(record['class'])
