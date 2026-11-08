"""Targeted standalone JVM/SDK compile, no Gradle/Cargo/ADB or shared output mutation."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import argparse
import zipfile
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/network-sharing"
OUT.mkdir(parents=True, exist_ok=True)
sys.stdout.reconfigure(encoding="utf-8")
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Android\Android Studio\jbr")) / "bin"
if not (JAVA / "javac.exe").exists():
    JAVA = Path(r"C:\Program Files\Android\Android Studio\jbr\bin")
SDK = Path(os.environ.get("ANDROID_HOME", str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")))
ANDROID = SDK / "platforms/android-37.0/android.jar"
if not ANDROID.exists():
    ANDROID = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platforms/android-37.0/android.jar"
assert ANDROID.exists(), "SDK 37 android.jar is required"
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
API = next((CACHE / "io.github.libxposed/api/102.0.0").rglob("*.aar"))
with zipfile.ZipFile(API) as archive:
    (OUT / "libxposed.jar").write_bytes(archive.read("classes.jar"))
JUNIT = next((CACHE / "junit/junit/4.13.2").rglob("*.jar"))
HAMCREST = next((CACHE / "org.hamcrest/hamcrest-core/1.3").rglob("*.jar"))
SOURCE = ROOT / "android/module/src/main/java/io/github/qiangjian/connect"
TESTS = ROOT / "android/module/src/test/java/io/github/qiangjian/connect"
sources = sorted(SOURCE.glob("NetworkShare*.java")) + sorted(SOURCE.glob("XiaomiNetwork*.java")) + sorted(SOURCE.glob("OplusNetwork*.java"))
tests = sorted(TESTS.glob("NetworkShare*Test.java"))
parser = argparse.ArgumentParser()
parser.add_argument("--test-class", action="append", choices=[p.stem for p in tests])
parser.add_argument("--compile-only", action="store_true")
args = parser.parse_args()
selected = [p for p in tests if not args.test_class or p.stem in args.test_class]
if args.compile_only:
    selected = []
evidence = OUT / ("verification-" + "-".join(args.test_class) + ".json" if args.test_class else "verification.json")
if args.compile_only:
    evidence = OUT / "verification-compile.json"
classes = OUT / "classes"
classes.mkdir(exist_ok=True)
classpath = os.pathsep.join(map(str, [ANDROID, OUT / "libxposed.jar", JUNIT, HAMCREST]))
commands = [
    [str(JAVA / "javac.exe"), "-J-Duser.language=en", "-encoding", "UTF-8", "--release", "17", "-cp", classpath, "-d", str(classes), *map(str, sources + tests)],
    [str(JAVA / "java.exe"), "-cp", os.pathsep.join([str(classes), classpath]), "org.junit.runner.JUnitCore",
     *["io.github.qiangjian.connect." + p.stem for p in selected]],
]
if args.compile_only:
    commands = commands[:1]
record = {"time_utc": datetime.now(timezone.utc).isoformat(), "scope": "standalone SDK 37 compilation and synthetic JVM tests; no device or APK validation",
          "sources": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources + tests + [Path(__file__)]},
          "executed_test_classes": [p.stem for p in selected], "runs": []}
apk = ROOT / "android/module/build/outputs/apk/debug/module-debug.apk"
record["existing_apk_not_tested"] = {"path": str(apk), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest() if apk.exists() else None}
for command in commands:
    run = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    record["runs"].append({"command": command, "returncode": run.returncode, "stdout": run.stdout, "stderr": run.stderr})
    evidence.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    print(run.stdout, end="")
    print(run.stderr, end="")
    if run.returncode:
        raise SystemExit(run.returncode)
print("Evidence: " + str(evidence.relative_to(ROOT)))
