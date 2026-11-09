"""Target only the new Bluetooth byte pump and affected child-channel I/O; isolated Cargo target."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import os
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/network-sharing/bearer-validation" / uuid.uuid4().hex
OUT.mkdir(parents=True)
sdk = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platforms/android-37.0/android.jar"
java = Path(r"C:\Program Files\Android\Android Studio\jbr\bin")
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
junit = next((cache / "junit/junit/4.13.2").rglob("*.jar"))
hamcrest = next((cache / "org.hamcrest/hamcrest-core/1.3").rglob("*.jar"))
main = ROOT / "android/module/src/main/java/io/github/yuejie/connect/NetworkShareBluetoothBearer.java"
test = ROOT / "android/module/src/test/java/io/github/yuejie/connect/NetworkShareBluetoothBearerTest.java"
rust = ROOT / "transport/src/stable_channel.rs"
manifest = ROOT / "build/network-sharing/stable-channel/Cargo.toml"
target = ROOT / "build/network-sharing/stable-channel/target"
sources = [main, test, rust, manifest, Path(__file__)]


def hashes():
    return {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}


record = {"utc": datetime.now(timezone.utc).isoformat(), "sources": hashes(), "runs": [],
          "scope": "SDK37 Java byte-pump checks and changed Rust in-flight queue I/O scope; no old 40 or media tests, no APK/device/real Bluetooth claim",
          "earlier_distinct_rust_tests": {"count": 12, "tool_chunk": "80e75f", "limit": "before tightening in-flight frame reservation; authorization/handshake logic unchanged since this run"},
          "clippy": {"tool_chunk": "ed0e1a", "result": "passed --all-targets -- -D warnings at current Rust SHA"}}
classpath = os.pathsep.join(map(str, [sdk, junit, hamcrest]))
commands = [
    [str(java / "javac.exe"), "--release", "17", "-encoding", "UTF-8", "-cp", classpath, "-d", str(OUT / "classes"), str(main), str(test)],
    [str(java / "java.exe"), "-cp", os.pathsep.join([str(OUT / "classes"), classpath]), "org.junit.runner.JUnitCore", "io.github.yuejie.connect.NetworkShareBluetoothBearerTest"],
    ["cargo", "test", "--offline", "--manifest-path", str(manifest), "--target-dir", str(target), "authenticated"],
    ["cargo", "check", "--offline", "--manifest-path", str(manifest), "--target-dir", str(target), "--target", "aarch64-linux-android"],
]
for command in commands:
    run = subprocess.run(command, cwd=ROOT, capture_output=True, encoding="utf-8", errors="replace")
    record["runs"].append({"command": command, "returncode": run.returncode, "stdout": run.stdout, "stderr": run.stderr})
    record["unchanged_during_check"] = record["sources"] == hashes()
    (OUT / "result.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    print(run.stdout, end=""); print(run.stderr, end="")
    if run.returncode: raise SystemExit(run.returncode)
print("Evidence: " + (OUT / "result.json").relative_to(ROOT).as_posix())
assert record["unchanged_during_check"], "Own bearer sources changed during validation"
