"""Read-only targeted DEX audit; no devices, user data, or runtime phone/SMS actions."""
from pathlib import Path
import hashlib
import json
import re
import zipfile
from loguru import logger
logger.remove()
from androguard.core.dex import DEX

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/calls-sms-validation'
OUT.mkdir(parents=True, exist_ok=True)
SAMPLES = ROOT / 'captures/device-interconnect-20261108'
targets = {
    'hyperos4/com.xiaomi.mirror/MirrorOS4.apk': {
        'Lcom/xiaomi/mirror/relay/m;': {'y0', 'T0', 'h2', 'release'},
        'Lcom/xiaomi/mirror/relay/F;': {'D'},
    },
    'coloros17/com.oplus.nearcomm/OplusNearComm.apk': {
        'Lcom/oplus/nearcomm/call/NearCall;': {'answerCall', 'hangupCall', 'getCcid'},
        'Lcom/oplus/nearcomm/call/NearCallList;': {'onAnswerCall', 'onHangupCall'},
        'Lcom/oplus/nearcomm/ptc/NearCommIPhoneProvider;': {'getSupportedDeviceTypes'},
        'Lcom/oplus/nearcomm/ptc/NearCommIWatchProvider;': {'getSupportedDeviceTypes'},
        'Lcom/oplus/nearcomm/ptc/NearCommSmsProvider;': {'getSupportedDeviceTypes'},
        'Lcom/oplus/nearcomm/sms/NearSmsTransfer;': {'updateLocalSmsStatus'},
    },
    'coloros17/com.oplus.linker/OPSynergy.apk': {
        'Lcom/oplus/linker/synergy/bus/PCSynergyReceive;': {'dealPhoneAccept'},
    },
}
records = []
found = {}
for relative, classes in targets.items():
    apk = SAMPLES / relative
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if not re.fullmatch(r'classes\d*\.dex', name):
                continue
            raw = z.read(name)
            dex = DEX(raw)
            for cls in dex.get_classes():
                if cls.get_name() not in classes:
                    continue
                for method in cls.get_methods():
                    if method.get_name() not in classes[cls.get_name()]:
                        continue
                    instructions = [f'{offset:04x}: {ins.get_name()} {ins.get_output()}' for offset, ins in method.get_instructions_idx()]
                    key = cls.get_name() + '->' + method.get_name()
                    found[key] = instructions
                    records.append({'apk': relative, 'apk_sha256': digest, 'dex': name,
                                    'dex_sha256': hashlib.sha256(raw).hexdigest(), 'class': cls.get_name(),
                                    'method': method.get_name(), 'descriptor': method.get_descriptor(), 'instructions': instructions})
missing = [cls + '->' + method for classes in targets.values() for cls, methods in classes.items() for method in methods if cls + '->' + method not in found]
assert not missing, f'Missing raw methods: {missing}'
for method in ('y0', 'T0', 'release'):
    instructions = found['Lcom/xiaomi/mirror/relay/m;->' + method]
    assert len(instructions) == 1 and 'return-void' in instructions[0], instructions
for method, fallback in [('onAnswerCall', 'getIncomingCall'), ('onHangupCall', 'getFirstCall')]:
    assert any(fallback in s for s in found['Lcom/oplus/nearcomm/call/NearCallList;->' + method])
assert any('->answer' in s for s in found['Lcom/oplus/nearcomm/call/NearCall;->answerCall'])
assert any('update_sms_status' in s for s in found['Lcom/oplus/nearcomm/sms/NearSmsTransfer;->updateLocalSmsStatus'])
(OUT / 'sample-bytecode.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'PASS: {len(records)} methods verified; Xiaomi relay message/listener no-ops and Oplus target fallback confirmed. Evidence: {OUT}')
