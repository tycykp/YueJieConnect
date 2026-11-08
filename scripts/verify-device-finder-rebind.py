"""Single targeted lifecycle race; no Android/Gradle/device/OEM or baseline test invocations."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
out = root / "build/device-finder-rebind"
out.mkdir(parents=True, exist_ok=True)
controller = root / "android/module/src/main/java/io/github/qiangjian/connect/DeviceFinderController.java"
test = root / "android/module/src/test/java/io/github/qiangjian/connect/DeviceFinderRebindRaceCheck.java"
java = Path(os.environ.get("JAVA_HOME", "")) / "bin"
if not (java / "javac.exe").exists():
    java = Path(r"C:\Program Files\Android\Android Studio\jbr\bin")
commands = [
    [java / "javac.exe", "--release", "17", "-encoding", "UTF-8", "-d", out / "classes", controller, test],
    [java / "java.exe", "-cp", out / "classes", "io.github.qiangjian.connect.DeviceFinderRebindRaceCheck"],
]
results = []
for command in commands:
    result = subprocess.run([str(v) for v in command], cwd=root, capture_output=True, text=True, errors="replace")
    results.append(dict(command=[str(v) for v in command], exit_code=result.returncode, output=result.stdout + result.stderr))
    print((result.stdout + result.stderr).strip())
    if result.returncode:
        break
record = dict(commands=results, source_sha256={str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [controller, test, Path(__file__)]})
(out / "verification.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
raise SystemExit(results[-1]["exit_code"])
