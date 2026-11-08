"""Check original one-way input dispatch/recycling and local SDK system focus interfaces."""
import hashlib
import json
import os
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
root = Path(__file__).resolve().parents[1]
relative = 'captures/device-interconnect-20261108/hyperos4/com.xiaomi.mirror/MirrorOS4.apk'
apk = root / relative
assert hashlib.sha256(apk.read_bytes()).hexdigest() == '9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356'
wanted = {
 'Lq4/A;': {'R(Lcom/xiaomi/mirror/message/Message;Ljava/lang/String;Z)V': ['MessageManagerImpl;->send']},
 'Lcom/xiaomi/mirror/message/MessageConvert;': {'encodeMsg(Lcom/xiaomi/mirror/message/Message;Z)[B': ['MessageFactory;->recycle']},
}
evidence = []
with zipfile.ZipFile(apk) as archive:
    for name in archive.namelist():
        if not name.startswith('classes') or not name.endswith('.dex'):
            continue
        raw = archive.read(name)
        for cls in DEX(raw).get_classes():
            if cls.get_name() not in wanted:
                continue
            methods = {m.get_name()+m.get_descriptor().replace(' ', ''): m for m in cls.get_methods()}
            trace = {}
            for signature, tokens in wanted[cls.get_name()].items():
                method = methods[signature]
                instructions = [i.get_name()+' '+i.get_output() for i in method.get_instructions()]
                for token in tokens:
                    assert any(token in line for line in instructions), token
                if cls.get_name() == 'Lq4/A;':
                    call = next(i for i, line in enumerate(instructions) if 'MessageManagerImpl;->send' in line)
                    # The exact native SendCallback argument is the const-null register, not a
                    # fictional reply schema. Keep the complete original instruction evidence.
                    assert 'const/4' in instructions[call-1] and instructions[call-1].endswith(', 0'), instructions[call-2:call+1]
                trace[signature] = instructions
            evidence.append(dict(apk=relative, dex=name, dex_sha256=hashlib.sha256(raw).hexdigest(),
                                 class_name=cls.get_name(), instructions=trace))
assert len(evidence) == len(wanted)
sdk = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/sources/android-37.0/android'
sdk_sources = {
 'inputmethodservice/InputMethodService.java': ['InputBinding getCurrentInputBinding()', 'boolean getCurrentInputStarted()', 'InputConnection getCurrentInputConnection()'],
 'view/inputmethod/InputBinding.java': ['int getUid()'],
}
for relative_sdk, tokens in sdk_sources.items():
    source = sdk / relative_sdk
    text = source.read_text(encoding='utf-8')
    assert all(token in text for token in tokens), relative_sdk
    evidence.append(dict(sdk=str(source), sha256=hashlib.sha256(source.read_bytes()).hexdigest(), signatures=tokens))
out = root / 'build/screen-native-input-sender-validation/native-flow.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(evidence, indent=2), encoding='utf-8')
print('PASS: 2 OEM methods for one-way input/recycling and 4 SDK system-focus signatures')
