"""Device smoke check: stop the companion app, then observe live OEM transport without app IPC."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
MODULE = "io.github.yuejie.connect"
HOSTS = {"3B161Q00YN200000": "com.oplus.linker", "b59f8656": "com.milink.service"}


def adb(serial, *args):
    return subprocess.check_output(["adb", "-s", serial, *args], timeout=20).decode("utf-8", "replace")


def snapshot(serial, host):
    processes = [line.split() for line in adb(serial, "shell", "ps", "-A", "-o", "PID,NAME").splitlines()[1:]]
    module = [parts[1] for parts in processes if len(parts) == 2 and
              (parts[1] == MODULE or parts[1].startswith(MODULE + ":"))]
    pids = [parts[0] for parts in processes if len(parts) == 2 and parts[1] == host]
    assert not module, f"{serial}: companion process running: {module}"
    assert len(pids) == 1 and pids[0].isdigit(), f"{serial}: OEM host missing"
    log = adb(serial, "logcat", "-d", "--pid=" + pids[0], "-s", "YueJieBridge:I", "*:S")
    states = re.findall(r"phase=(\w+) error=(\S+) errno=(\S+) rpc=(\d+)/(\d+)/(\d+)", log)
    assert states, f"{serial}: no core status in OEM process"
    state = states[-1]
    assert state[0] == "active", f"{serial}: core phase {state[0]}"
    assert state[1:3] == ("null", "null"), f"{serial}: transport error"
    return {"pid": int(pids[0]), "phase": state[0], "sent": int(state[3]),
            "received": int(state[4]), "responses": int(state[5]), "module_processes": module}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--duration", type=int, default=60)
    args = parser.parse_args()
    assert 10 <= args.duration <= 300
    for serial in HOSTS:
        adb(serial, "shell", "input", "keyevent", "3")
        adb(serial, "shell", "am", "force-stop", MODULE)
    started = time.monotonic()
    samples = []
    while True:
        samples.append({s: snapshot(s, host) for s, host in HOSTS.items()})
        if time.monotonic() - started >= args.duration:
            break
        time.sleep(5)
    for serial in HOSTS:
        first, last = samples[0][serial], samples[-1][serial]
        assert first["pid"] == last["pid"], f"{serial}: OEM host restarted during observation"
        assert last["responses"] > first["responses"], f"{serial}: no fresh peer responses"
    apk = ROOT / "android/module/build/outputs/apk/debug/module-debug.apk"
    result = {"passed": True, "duration_seconds": round(time.monotonic() - started, 1),
              "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "samples": samples}
    destination = ROOT / "build/native-host-validation/background.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({"passed": True, "duration_seconds": result["duration_seconds"],
                      "evidence": str(destination)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
