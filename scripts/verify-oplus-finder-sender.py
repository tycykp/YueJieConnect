"""New local sender behavior, real Android API compilation and new original DEX signatures only."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/device-finder-repair/sender'
OUT.mkdir(parents=True, exist_ok=True)
MAIN = ROOT / 'android/module/src/main/java/io/github/qiangjian/connect'
TEST = ROOT / 'android/module/src/test/java/io/github/qiangjian/connect/OplusFinderSenderCheck.java'
JAVA = Path('C:/Program Files/Android/Android Studio/jbr/bin')
ANDROID = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platforms/android-37.0/android.jar'
API = ROOT / 'build/device-finder-validation/libxposed-api.jar'
JSON = ROOT / 'build/file-transfer-validation/json-src/org/json'
if not (JSON / 'JSONObject.java').exists():
    JSON = ROOT / 'build/notifications-validation/json-src/org/json'
SOURCES = [MAIN / name for name in ('OplusFinderSender.java', 'OplusFinderChannelAdapter.java', 'OplusFinderUiAdapter.java')]
SHARED = [MAIN / name for name in ('BridgeClient.java', 'IBridge.java', 'NativeLogicalSessions.java')]
commands = []
cache_path = OUT / 'command-cache.json'
cache = json.loads(cache_path.read_text(encoding='utf-8')) if cache_path.exists() else {}

def run(command, inputs):
    hashes = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
    key = hashlib.sha256(json.dumps([str(v) for v in command]).encode()).hexdigest()
    prior = cache.get(key)
    if prior and prior['inputs'] == hashes and prior['result']['exit_code'] == 0:
        result = dict(prior['result'], reused=True)
    else:
        process = subprocess.run([str(v) for v in command], capture_output=True, text=True, errors='replace', timeout=40)
        result = dict(command=[str(v) for v in command], exit_code=process.returncode, output=process.stdout + process.stderr)
        cache[key] = dict(inputs=hashes, result=result)
        cache_path.write_text(json.dumps(cache, indent=2), encoding='utf-8')
    commands.append(result)
    print(('REUSED ' if result.get('reused') else '') + result['output'].strip())
    if result['exit_code']:
        (OUT / 'failure.json').write_text(json.dumps(commands, indent=2), encoding='utf-8')
        raise SystemExit(result['exit_code'])

pure = [SOURCES[0], TEST, *sorted(JSON.glob('*.java'))]
run([JAVA/'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-d', OUT/'jvm', *pure], pure)
run([JAVA/'java.exe', '-cp', OUT/'jvm', 'io.github.qiangjian.connect.OplusFinderSenderCheck'],
    [*pure, *sorted((OUT/'jvm').rglob('*.class'))])
run([JAVA/'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-cp', str(ANDROID)+os.pathsep+str(API),
     '-d', OUT/'android', *SOURCES, *SHARED, TEST], [*SOURCES,*SHARED,TEST,ANDROID,API])

# Pin only the new sender adapter's reflection surface; old receiver/playback/strategy evidence is reused.
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
APK = ROOT/'captures/device-finder-20261108/coloros17/com.coloros.findmyphone/FindMyPhone.apk'
apk_hash = hashlib.sha256(APK.read_bytes()).hexdigest()
assert apk_hash == '6e24b00e3c8689783dcd9b61287cc99bb6ab2dd688af915a7ea794666963596d'
B = 'Lcom/coloros/findmyphone/channel/api/bean/'
required = {
    'Lcom/coloros/findmyphone/channel/core/FindChannelProviderImpl;': {
        f'sendMessage({B}ChannelMessage;LC1/d;)V',
        f'sendMessageByChannel({B}ChannelMessage;{B}FindChannelType;LC1/d;Ljava/lang/String;)V'},
    B+'ChannelMessage;': {f'getReceiver(){B}MessageReceiver;', 'getMessageId()Ljava/lang/String;', f'getMsgType(){B}ChannelMessageType;'},
    B+'MessageReceiver;': {f'getDeviceInfo(){B}BaseChannelDeviceInfo;'},
    B+'BaseChannelDeviceInfo;': {'getDeviceId()Ljava/lang/String;', 'getDeviceType()Lcom/coloros/findmyphone/basecommon/biz/entity/DeviceType;'},
    B+'ChannelSendResult;': {f'<init>({B}FindChannelType;La1/a;{B}ChannelMessage;)V'},
    'LC1/d;': {'b(La1/a;)V'},
    'La1/a;': {'b(Ljava/lang/Object;)La1/a;', 'a(Lcom/coloros/findmyphone/basecommon/error/FindError;)La1/a;',
              '<init>(Lcom/coloros/findmyphone/basecommon/error/FindError;Ljava/lang/Object;)V'},
    'Lcom/coloros/findmyphone/basecommon/error/FindError;': {'<init>(ILjava/lang/String;)V'},
}
dex_evidence=[]
previous_path=OUT/'verification.json'
previous=json.loads(previous_path.read_text(encoding='utf-8')) if previous_path.exists() else {}
prior_spec={row['class_name']:set(row['signatures']) for row in previous.get('new_dex_signatures',[])}
dex_reused=False
with zipfile.ZipFile(APK) as archive:
    raw=archive.read('classes.dex')
    if previous.get('apk_sha256')==apk_hash and previous.get('dex_sha256')==hashlib.sha256(raw).hexdigest() and prior_spec==required:
        dex_evidence=previous['new_dex_signatures']
        required.clear()
        dex_reused=True
    for cls in ([] if dex_reused else DEX(raw).get_classes()):
        if cls.get_name() not in required:
            continue
        expected=required.pop(cls.get_name())
        actual={method.get_name()+method.get_descriptor().replace(' ','') for method in cls.get_methods()}
        assert expected <= actual, (cls.get_name(), expected-actual)
        dex_evidence.append(dict(class_name=cls.get_name(), signatures=sorted(expected)))
assert not required, required
record=dict(commands=commands, source_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
                                            for p in [*SOURCES,*SHARED,TEST,Path(__file__),*sorted(JSON.glob('*.java'))]},
            dependencies_sha256={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in (ANDROID,API)},
            apk_sha256=apk_hash, dex_sha256=hashlib.sha256(raw).hexdigest(), new_dex_signatures=dex_evidence,
            new_dex_signatures_reused=dex_reused,
            scope='New sender state-machine and typed OEM callback adapter compilation/signatures; no UI entry injection, cloud identity, build/install/session/playback or real OEM execution')
(OUT/'verification.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
print(f'PASS real Android API compilation; {"REUSED" if dex_reused else "verified"} {len(dex_evidence)} original sender DEX class signatures')
