"""Compile only the new OEM observation wiring/probe against SDK37; no tests/builds/ADB."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import os
import subprocess
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
OUT = ROOT / "build/network-sharing/probe-compile" / stamp
OUT.mkdir(parents=True)
main = ROOT / "android/module/src/main/java/io/github/qiangjian/connect"
sources = sorted(main.glob("NetworkShare*.java")) + sorted(main.glob("XiaomiNetwork*.java")) + sorted(main.glob("OplusNetwork*.java"))
sources += [ROOT / "android/module/src/debug/java/io/github/qiangjian/connect/NetworkShareNativeProbe.java"]
java = Path(r"C:\Program Files\Android\Android Studio\jbr\bin\javac.exe")
android = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platforms/android-37.0/android.jar"
cache = Path.home() / ".gradle/caches/modules-2/files-2.1/io.github.libxposed/api/102.0.0"
with zipfile.ZipFile(next(cache.rglob("*.aar"))) as archive:
    (OUT / "libxposed.jar").write_bytes(archive.read("classes.jar"))


def hashes():
    return {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources + [Path(__file__)]}


before = hashes()
command = [str(java), "-J-Duser.language=en", "-encoding", "UTF-8", "--release", "17", "-cp",
           os.pathsep.join([str(android), str(OUT / "libxposed.jar")]), "-d", str(OUT / "classes"), *map(str, sources)]
run = subprocess.run(command, cwd=ROOT, capture_output=True, encoding="utf-8", errors="replace")
record = {"utc": datetime.now(timezone.utc).isoformat(), "scope": "SDK37 standalone compile of NetworkShareBinding debug-only read-only observation wiring; no JVM business tests, APK or devices",
          "sources": before, "unchanged_during_compile": before == hashes(), "command": command,
          "returncode": run.returncode, "stdout": run.stdout, "stderr": run.stderr, "device_operations": []}
(OUT / "result.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
print(run.stdout, end=""); print(run.stderr, end="")
print("Evidence: " + (OUT / "result.json").relative_to(ROOT).as_posix())
raise SystemExit(run.returncode if record["unchanged_during_compile"] else 2)
