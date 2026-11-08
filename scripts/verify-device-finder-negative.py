"""Additional isolated negative cases. Never contacts devices or reruns the baseline/OEM suites."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
out = root / "build/device-finder-negative"
out.mkdir(parents=True, exist_ok=True)
src = root / "android/module/src/main/java/io/github/qiangjian/connect"
test = root / "android/module/src/test/java/io/github/qiangjian/connect/DeviceFinderAdversarialCheck.java"
production = sorted(p for pattern in ("DeviceFinder*.java", "XiaomiFinder*.java", "OplusFinder*.java") for p in src.glob(pattern))
before = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in production}
jdk = Path(os.environ.get("JAVA_HOME", "")) / "bin"
if not (jdk / "javac.exe").exists():
    jdk = Path(r"C:\Program Files\Android\Android Studio\jbr\bin")
inputs = [src / "DeviceFinderController.java", src / "XiaomiFinderCodec.java", test]
commands = [
    [jdk / "javac.exe", "--release", "17", "-encoding", "UTF-8", "-d", out / "classes", *inputs],
    [jdk / "java.exe", "-cp", out / "classes", "io.github.qiangjian.connect.DeviceFinderAdversarialCheck"],
]
results = []
for command in commands:
    result = subprocess.run([str(a) for a in command], cwd=root, capture_output=True, text=True, errors="replace")
    results.append(dict(command=[str(a) for a in command], exit_code=result.returncode, output=result.stdout + result.stderr))
    print((result.stdout + result.stderr).strip())
    if result.returncode:
        break
after = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in production}
assert before == after, "Finder production sources changed during negative validation"
report = dict(commands=results, production_sha256=before,
              test_sha256=hashlib.sha256(test.read_bytes()).hexdigest(),
              script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              scope="Additional host JVM negative cases; no device, APK, baseline behavior, OEM DEX or shared build invocation")
(out / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
raise SystemExit(results[-1]["exit_code"])
