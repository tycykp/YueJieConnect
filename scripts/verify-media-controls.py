"""Focused media validation. Isolated javac/JUnit; optional pinned OEM DEX check. No devices/build locks."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
import zipfile
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/media-controls"
MAIN = ROOT / "android/module/src/main/java/io/github/yuejie/connect"
TESTS = sorted((ROOT / "android/module/src/test/java/io/github/yuejie/connect").glob("MediaControl*Test.java"))
SOURCES = sorted([*MAIN.glob("MediaControl*.java"), *MAIN.glob("XiaomiMedia*.java"), *MAIN.glob("OplusMedia*.java")])
SHARED = [MAIN / "BridgeClient.java", MAIN / "IBridge.java", MAIN / "NativeLogicalSessions.java"]
FIXTURE = ROOT / "android/module/src/debug/java/io/github/yuejie/connect/MediaControlFixture.java"

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def run(command, name):
    result = subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    (OUT / name).write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"{name}: {(result.stdout + result.stderr)[-2500:]}")
    return {"command": list(map(str, command)), "exit_code": result.returncode, "output": name}

def code():
    selected_tests = [p for p in TESTS if not args.test_classes or p.stem in args.test_classes]
    assert selected_tests, "No selected tests"
    input_paths = [*SOURCES, *SHARED, *selected_tests, FIXTURE, Path(__file__)]
    inputs_before = {str(p.relative_to(ROOT)): sha(p) for p in input_paths}
    jdk = Path(os.environ.get("MEDIA_JAVA_HOME", "C:/Program Files/Android/Android Studio/jbr"))
    sdk = Path(os.environ.get("MEDIA_ANDROID_HOME", os.environ.get("ANDROID_HOME", Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")))
    if not (sdk / "platforms/android-37.0/android.jar").is_file():
        sdk = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"
    android = sdk / "platforms/android-37.0/android.jar"
    assert android.is_file(), "Set MEDIA_ANDROID_HOME to a complete API 37 SDK"
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    junit = next((cache / "junit/junit/4.13.2").rglob("junit-4.13.2.jar"))
    hamcrest = next((cache / "org.hamcrest/hamcrest-core/1.3").rglob("hamcrest-core-1.3.jar"))
    xposed_aar = next((cache / "io.github.libxposed/api/102.0.0").rglob("*.aar"))
    xposed = OUT / "xposed-api.jar"
    with zipfile.ZipFile(xposed_aar) as archive:
        xposed.write_bytes(archive.read("classes.jar"))
    classes, tests = OUT / "classes", OUT / "tests"
    # Real installed SDK JSON implementation, with Android-only annotations removed for host JVM.
    # It shadows android.jar's throwing stubs in the focused wire tests; no handwritten JSON fake.
    json_dir = OUT / "json-src/org/json"
    json_dir.mkdir(parents=True, exist_ok=True)
    json_ledger, json_sources = [], []
    for source in sorted((sdk / "sources/android-37.0/org/json").glob("*.java")):
        content = source.read_text(encoding="utf-8")
        content = re.sub(r"^import (?:static )?(?:android\.|libcore\.).*;\r?\n", "", content, flags=re.M)
        content = re.sub(r"@SystemApi\(client = MODULE_LIBRARIES\)", "", content)
        content = re.sub(r"@(?:UnsupportedAppUsage|NonNull|Nullable)\b", "", content)
        target = json_dir / source.name
        target.write_text(content, encoding="utf-8")
        json_sources.append(target)
        json_ledger.append({"path": str(source), "sha256": sha(source), "host_sha256": sha(target)})
    assert len(json_sources) == 6, "Expected six installed SDK Android JSON sources"
    steps = [run([jdk / "bin/javac.exe", "--release", "17", "-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, [android, xposed])), "-d", classes, *SOURCES, *SHARED, FIXTURE], "compile.txt")]
    steps.append(run([jdk / "bin/javac.exe", "--release", "17", "-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, [classes, junit, android])), "-d", tests, *json_sources, *selected_tests], "test-compile.txt"))
    steps.append(run([jdk / "bin/java.exe", "-cp", os.pathsep.join(map(str, [tests, classes, junit, hamcrest, android])),
                      "org.junit.runner.JUnitCore", *["io.github.yuejie.connect." + p.stem for p in selected_tests]], "tests.txt"))
    inputs_after = {str(p.relative_to(ROOT)): sha(p) for p in input_paths}
    races = [name for name in inputs_before if inputs_before[name] != inputs_after[name]]
    report = {"utc": datetime.now(timezone.utc).isoformat(), "sources": {str(p.relative_to(ROOT)): inputs_before[str(p.relative_to(ROOT))] for p in [*SOURCES, *selected_tests, FIXTURE, Path(__file__)]},
              "selected_tests": [p.stem for p in selected_tests],
              "shared_compile_dependencies": {str(p.relative_to(ROOT)): inputs_before[str(p.relative_to(ROOT))] for p in SHARED},
              "inputs_before": inputs_before, "inputs_after": inputs_after, "input_races": races,
              "android_jar_sha256": sha(android), "xposed_aar_sha256": sha(xposed_aar), "json_sources": json_ledger,
              "steps": steps, "scope": "isolated compile, pure-Java state/route, real SDK JSON wire behavior; no APK/device/native-entry result"}
    (OUT / "code-validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    assert not races, f"Validation inputs changed during the run: {races}"
    print((OUT / "tests.txt").read_text(encoding="utf-8").strip())

def dex(round2=False):
    from androguard.core.dex import DEX
    from loguru import logger
    logger.remove()
    x = "Lcom/xiaomi/mirror/message/"
    o = "Lcom/oplus/synergy/scene/"
    string = "Ljava/lang/String;"
    required = {
        "hyperos4/com.xiaomi.mirror/MirrorOS4.apk": ("9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356", ["classes2.dex"], {
            "Lcom/xiaomi/mirror/audio/AudioUtils;": {"setStreamVolume(II)V"},
            x + "MediaSessionControlMessage;": {f"generateResultMsg(JZ){x}MediaSessionControlMessage;"},
            x + "CtrlMessage;": {"<init>()V"},
            x + "VolumeValueMessage;": {"<init>()V"},
            "Lj2/G;": {f"q({x}MediaSessionControlMessage;)V", f"r({x}MediaSessionControlMessage;Z)V"},
            "Lr3/e;": {f"b({x}CtrlMessage;)V", "i(I)V"},
            "Lr3/q;": {"z0(I)V"},
            "Ls2/b;": {f"j({x}Message;{x}MessageManager$SendCallback;Lcom/xiaomi/continuity/channel/PacketTransferProgressCallback;)V"},
        }),
        "coloros17/com.oplus.linker/OPSynergy.apk": ("dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b", ["classes6.dex"], {
            o + "platform/executor/VolumeController;": {"setVolume(IZ)Z", "setVolume([BZ)Z"},
            o + "platform/executor/MediaSessionController;": {f"pause({string})Z", f"togglePlayPause({string})Z", f"seekForward({string})Z", f"seekRewind({string})Z"},
            o + "common/proto/SceneSenseProto$VolumeControlPayload;": {f"parseFrom([B){o}common/proto/SceneSenseProto$VolumeControlPayload;", "getVolume()I"},
            o + "common/model/ControlResult;": {f"<init>(Z{string}I)V"},
        }),
    }
    if round2:
        required = {
            "hyperos4/com.xiaomi.mirror/MirrorOS4.apk": ("9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356", ["classes.dex", "classes2.dex"], {
                "Lcom/xiaomi/mirror/k0;": {"h()Ljava/lang/String;"},
                x + "MessageManager$SendCallback;": {f"onException(Lcom/xiaomi/mirror/k0;Ljava/lang/Throwable;)V", f"onReply(Lcom/xiaomi/mirror/k0;{x}Message;)V"},
                x + "MessageManagerImpl;": {f"send({x}Message;Lcom/xiaomi/mirror/k0;{x}MessageManager$SendCallback;)V"},
            }),
            "coloros17/com.oplus.linker/OPSynergy.apk": ("dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b", ["classes6.dex"], {
                o + "platform/executor/MediaSessionController;": {f"skipToNext({string})Z", f"skipToPrevious({string})Z"},
                o + "common/model/ControlCommand;": {"<clinit>()V"},
            }),
        }
    reports = []
    for relative, (expected, entries, classes) in required.items():
        apk = ROOT / "captures/device-interconnect-20261108" / relative
        assert sha(apk) == expected, f"Original APK changed: {relative}"
        evidence = []
        with zipfile.ZipFile(apk) as archive:
            for entry in entries:
                data = archive.read(entry)
                parsed = DEX(data)
                for cls in parsed.get_classes():
                    if cls.get_name() not in classes:
                        continue
                    actual = {m.get_name() + m.get_descriptor().replace(" ", ""): m for m in cls.get_methods()}
                    expected_methods = classes.pop(cls.get_name())
                    assert expected_methods <= actual.keys(), (cls.get_name(), expected_methods - actual.keys())
                    methods = []
                    for signature in sorted(expected_methods):
                        method = actual[signature]
                        # Preserve targeted raw instructions for verifying called sinks and native non-null checks.
                        instructions = [i.get_name() + " " + i.get_output() for i in method.get_instructions()]
                        methods.append({"descriptor": signature, "instructions": instructions})
                    fields = [{"name": f.get_name(), "type": f.get_descriptor()} for f in cls.get_fields()]
                    evidence.append({"class": cls.get_name(), "dex": entry, "dex_sha256": hashlib.sha256(data).hexdigest(), "methods": methods, "fields": fields})
                del parsed
        assert not classes, list(classes)
        reports.append({"apk": relative, "apk_sha256": expected, "classes": evidence})
    (OUT / ("round2-dex-evidence.json" if round2 else "dex-evidence.json")).write_text(json.dumps(reports, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Verified {sum(len(r['classes']) for r in reports)} OEM DEX classes; no runtime capability claim.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--code-only", action="store_true")
    parser.add_argument("--dex-only", action="store_true")
    parser.add_argument("--round2-dex-only", action="store_true")
    parser.add_argument("--test-classes", nargs="+", choices=[p.stem for p in TESTS])
    parser.add_argument("--output-dir", type=Path, default=OUT)
    args = parser.parse_args()
    OUT = args.output_dir.resolve()
    if sum([args.code_only, args.dex_only, args.round2_dex_only]) > 1:
        parser.error("choose one mode")
    OUT.mkdir(parents=True, exist_ok=True)
    if not args.dex_only and not args.round2_dex_only:
        code()
    if not args.code_only:
        dex(args.round2_dex_only)
