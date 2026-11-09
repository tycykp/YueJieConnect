"""Verify finder-only JVM behavior, Android compilation and original OEM DEX signatures."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/device-finder-validation"
OUT.mkdir(parents=True, exist_ok=True)
SRC = ROOT / "android/module/src/main/java/io/github/yuejie/connect"
java = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Android\Android Studio\jbr")) / "bin"
if not (java / "javac.exe").exists():
    java = Path(r"C:\Program Files\Android\Android Studio\jbr\bin")
sdk = Path(os.environ.get("ANDROID_HOME", str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")))
android = sdk / "platforms/android-37.0/android.jar"
if not android.exists():
    android = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platforms/android-37.0/android.jar"
sources = sorted(p for glob in ("DeviceFinder*.java", "XiaomiFinder*.java", "OplusFinder*.java") for p in SRC.glob(glob))
test = ROOT / "android/module/src/test/java/io/github/yuejie/connect/DeviceFinderLogicTest.java"
commands = []
checkpoint = OUT / "command-cache.json"
cache = json.loads(checkpoint.read_text(encoding="utf-8")) if checkpoint.exists() else {}


def run(argv, inputs):
    key = hashlib.sha256(json.dumps([str(v) for v in argv]).encode()).hexdigest()
    hashes = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
    prior = cache.get(key)
    if prior and prior["inputs"] == hashes and prior["result"]["exit_code"] == 0:
        commands.append(prior["result"])
        print("REUSED unchanged verified command: " + str(argv[0].name))
        return
    result = subprocess.run([str(v) for v in argv], cwd=ROOT, capture_output=True, text=True, errors="replace")
    commands.append(dict(command=[str(v) for v in argv], exit_code=result.returncode, output=result.stdout + result.stderr))
    cache[key] = dict(inputs=hashes, result=commands[-1])
    checkpoint.write_text(json.dumps(cache, ensure_ascii=False, indent=2), encoding="utf-8")
    if result.returncode:
        (OUT / "failure.json").write_text(json.dumps(commands, indent=2), encoding="utf-8")
        raise RuntimeError(result.stdout + result.stderr)
    print((result.stdout + result.stderr).strip())


pure = [SRC / "DeviceFinderController.java", SRC / "XiaomiFinderCodec.java", test]
run([java / "javac.exe", "--release", "17", "-encoding", "UTF-8", "-d", OUT / "jvm", *pure], pure)
run([java / "java.exe", "-cp", OUT / "jvm", "io.github.yuejie.connect.DeviceFinderLogicTest"], [*pure, *sorted((OUT / "jvm").rglob("*.class"))])
shared = [SRC / "BridgeClient.java", SRC / "IBridge.java", SRC / "NativeLogicalSessions.java"]
aar = next((Path(os.environ["USERPROFILE"]) / ".gradle/caches/modules-2/files-2.1/io.github.libxposed/api/102.0.0").rglob("*.aar"))
api = OUT / "libxposed-api.jar"
with zipfile.ZipFile(aar) as z:
    content = z.read("classes.jar")
    if not api.exists() or api.read_bytes() != content:
        api.write_bytes(content)
run([java / "javac.exe", "--release", "17", "-encoding", "UTF-8", "-cp", str(android) + os.pathsep + str(api), "-d", OUT / "android", *sources, *shared], [*sources, *shared, android, api])

from androguard.core.dex import DEX
from loguru import logger
logger.remove()
apk = ROOT / "captures/device-interconnect-20261108/hyperos4/com.milink.service/base.apk"
apk_sha = hashlib.sha256(apk.read_bytes()).hexdigest()
assert apk_sha == "4d4ec36791dcc58b12efde494d24d0a5c8c219213433c1f986996c5277f95bd3"
base = "Lcom/miui/circulate/ringfind/"
callback = base + "runtime/impl/a$b;"
args = base + "runtime/ui/StartRingArgs;"
required = {
    base + "runtime/impl/b;": {"d()Z", "m()V", f"k({args})V", f"e(Landroid/content/Context;{callback})V", f"f({callback})V"},
    callback: {"a()V", "b()V", "c()V"},
    args: {"<init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"},
    base + "sc/d;": {"f(Ljava/lang/String;[B)I"},
    base + "sc/g$a;": {"a(I)[B", "b()[B"},
    base + "sc/g$c;": {"a(I)[B", "b(Ljava/lang/String;Ljava/lang/String;)[B"},
    base + "sc/g$d;": {"a(I)[B", "b()[B"},
    base + "runtime/sharechannel/RequestReceiver;": {"onReceive(Landroid/content/Context;Landroid/content/Intent;)V"},
    base + "sc/ui2client/SCClient;": {"isSupportGetStatus(Ljava/lang/String;)Z"},
    "Lcom/miui/circulate/world/sticker/blecard/PhoneCard;": {"M3()V"},
    "Lcom/miui/circulate/world/sticker/blecard/BleView;": {"w1()V", "j4()V"},
}
evidence = []
previous = OUT / "verification.json"
cached_evidence = json.loads(previous.read_text(encoding="utf-8")) if previous.exists() else {}
spec_sha = hashlib.sha256(json.dumps({name: sorted(methods) for name, methods in required.items()}, sort_keys=True).encode()).hexdigest()
cached_spec = {entry["class_name"]: entry["methods"] for entry in cached_evidence.get("signatures", [])}
cached_spec_sha = hashlib.sha256(json.dumps(cached_spec, sort_keys=True).encode()).hexdigest()
with zipfile.ZipFile(apk) as archive:
    raw = archive.read("classes2.dex")
    reusable = (cached_evidence.get("apk_sha256") == apk_sha
                and cached_evidence.get("dex_sha256") == hashlib.sha256(raw).hexdigest() and cached_spec_sha == spec_sha)
    if reusable:
        evidence = cached_evidence["signatures"]
        required.clear()
        print("REUSED unchanged verified OEM DEX signatures")
    for cls in ([] if reusable else DEX(raw).get_classes()):
        name = cls.get_name()
        if name not in required:
            continue
        methods = {m.get_name() + m.get_descriptor().replace(" ", ""): m for m in cls.get_methods()}
        selected = required.pop(name)
        assert selected <= methods.keys(), (name, selected - methods.keys())
        fields = [f.get_name() + ":" + f.get_descriptor() for f in cls.get_fields()]
        evidence.append(dict(class_name=name, methods=sorted(selected), fields=fields,
                             instructions={sig: [f"{ins.get_name()} {ins.get_output()}" for ins in methods[sig].get_instructions()]
                                           for sig in selected if "/sc/g$" in name}))
assert not required, required
record = dict(apk_sha256=apk_sha, dex_sha256=hashlib.sha256(raw).hexdigest(), signatures=evidence,
              commands=commands, source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sources, *shared, test, Path(__file__)]},
              scope="JVM logic and Android source compilation; no APK install, no physical-device or OEM-UI result")
(OUT / "verification.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"PASS {len(evidence)} original DEX classes; {len(sources)} Android sources compiled. Evidence: {OUT / 'verification.json'}")
