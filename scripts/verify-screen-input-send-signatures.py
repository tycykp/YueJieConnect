"""Validate native input sender/DTO signatures and the local SDK target-UID injection contract."""
import hashlib
import json
import os
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
root = Path(__file__).resolve().parents[1]
apk = root / 'captures/device-interconnect-20261108/hyperos4/com.xiaomi.mirror/MirrorOS4.apk'
expected = '9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356'
assert hashlib.sha256(apk.read_bytes()).hexdigest() == expected, 'OEM APK changed'
message = 'Lcom/xiaomi/mirror/message/'
peer = 'Lcom/xiaomi/mirror/k0;'
wanted = {
    peer: ({'h()Ljava/lang/String;'}, {}),
    message+'MouseMessage;': (set(), {'screenId':'I','action':'I','x':'I','y':'I','xAxis':'F','yAxis':'F','metaInfo':'I'}),
    message+'KeyMessage;': (set(), {'screenId':'I','keyCode':'I','down':'Z','text':'Ljava/lang/String;','metaInfo':'I'}),
    message+'MessageManagerImpl;': ({'send('+message+'Message;'+peer+message+'MessageManager$SendCallback;)V'}, {}),
    message+'MessageManager$SendCallback;': ({'onException('+peer+'Ljava/lang/Throwable;)V','onReply('+peer+message+'Message;)V'}, {}),
    message+'MessageFactory;': ({'recycle('+message+'Recyclable;)V'}, {}),
}
found=set()
evidence=[]
with zipfile.ZipFile(apk) as archive:
    for name in archive.namelist():
        if not name.startswith('classes') or not name.endswith('.dex'): continue
        data=archive.read(name)
        for cls in DEX(data).get_classes():
            if cls.get_name() not in wanted: continue
            methods,fields=wanted[cls.get_name()]
            actual_methods={m.get_name()+m.get_descriptor().replace(' ','') for m in cls.get_methods()}
            actual_fields={f.get_name():f.get_descriptor() for f in cls.get_fields()}
            assert methods <= actual_methods, (cls.get_name(),methods-actual_methods)
            assert all(actual_fields.get(k)==v for k,v in fields.items()), cls.get_name()
            evidence.append(dict(class_name=cls.get_name(),methods=sorted(methods),fields=fields,dex=name,
                                 dex_sha256=hashlib.sha256(data).hexdigest()))
            found.add(cls.get_name())
assert wanted.keys() <= found
sdk=Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/sources/android-37.0/android'
sdk_contracts={
    'hardware/input/InputManager.java':[
        'boolean injectInputEvent(InputEvent event, int mode, int targetUid)',
        'system will only consider injecting the input event',
        'into windows owned by the provided uid'],
    'view/InputEvent.java':['setDisplayId(int displayId)'],
    'view/MotionEvent.java':['setActionButton(int button)','ACTION_BUTTON_PRESS','ACTION_BUTTON_RELEASE','AXIS_HSCROLL','AXIS_VSCROLL'],
    'view/KeyEvent.java':['getMaxKeyCode()','FLAG_CANCELED','META_CTRL_LEFT_ON','META_CAPS_LOCK_ON'],
}
sdk_evidence=[]
for relative,tokens in sdk_contracts.items():
    path=sdk/relative
    content=path.read_text(encoding='utf-8')
    assert all(token in content for token in tokens), relative
    sdk_evidence.append(dict(path=str(path),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),tokens=tokens))
out=root/'build/screen-desktop-input-validation/native-signatures.json'
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(dict(apk=str(apk),apk_sha256=expected,oem=evidence,host_sdk=sdk_evidence,
                              native_device_injection_verified=False),indent=2),encoding='utf-8')
print(f'PASS: {len(evidence)} OEM DEX classes, {sum(len(e["methods"]) for e in evidence)} methods, '
      f'{sum(len(e["fields"]) for e in evidence)} fields; Android 37 SDK targetUid/display/button contracts')
