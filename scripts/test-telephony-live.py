"""Exercise fixed synthetic call events through both injected phone processes.

Requires a debug APK and a connected session with both call grants. Does not dial
or touch audio. A successful result is NOT real incoming-call/audio acceptance.
"""
import json
import re
import subprocess
import time
import uuid
from pathlib import Path
from debug_control import command

PHONE, PAD = "3B161Q00YN200000", "b59f8656"
OUT = Path(__file__).resolve().parents[1] / "build/calls-sms-validation/live"
OUT.mkdir(parents=True, exist_ok=True)


def probe(serial, action):
    request = uuid.uuid4().hex
    subprocess.run(["adb", "-s", serial, "shell", "am", "broadcast", "-a",
                    "io.github.yuejie.connect.CALL_PROBE", "-p", "com.android.phone",
                    "--es", "action", action, "--es", "request", request],
                   check=True, capture_output=True, timeout=15)
    until = time.monotonic() + 15
    while time.monotonic() < until:
        log = subprocess.check_output(["adb", "-s", serial, "shell", "logcat", "-d",
                                       "-v", "brief", "-s", "YueJieCallTest:I", "*:S"], timeout=10).decode("utf-8", "replace")
        for line in log.splitlines():
            if request + " " in line:
                return json.loads(line.split(request + " ", 1)[1])
        time.sleep(.3)
    raise TimeoutError(action + ": no injected-process response")


def main():
    report = {"scope": "Synthetic native UI and authenticated control routing; no real call or audio", "passed": False}
    try:
        states = {s: command(s, {"op": "bridge_status"})["bridge"] for s in (PHONE, PAD)}
        assert states[PHONE]["session"] == states[PAD]["session"]
        for serial, operation in [(PHONE, "telephony_call_state"), (PAD, "telephony_call_control")]:
            assert states[serial]["phase"] == "active"
            assert operation in states[serial]["projection"]["operations"]
        report["missing"] = probe(PAD, "missing")
        assert report["missing"].get("code") == "call_missing"
        # The native relay gate requires an unlocked, interactive tablet.
        subprocess.run(["adb", "-s", PAD, "shell", "input", "keyevent", "KEYCODE_WAKEUP"], check=True)
        subprocess.run(["adb", "-s", PAD, "shell", "wm", "dismiss-keyguard"], check=True)
        try:
            report["ringing"] = probe(PHONE, "ringing")
            assert report["ringing"].get("result", {}).get("data", {}).get("effect") == "created", report["ringing"]
            until = time.monotonic() + 6
            report["native_ui_observed"] = False
            while time.monotonic() < until:
                activities = subprocess.check_output(["adb", "-s", PAD, "shell", "dumpsys", "activity", "activities"], timeout=10).decode("utf-8", "replace")
                if re.search(r"(?:topResumedActivity|mResumedActivity)[^\n]*com\.android\.incallui", activities):
                    report["native_ui_observed"] = True
                    break
                time.sleep(.5)
            (OUT / "synthetic-incoming.png").write_bytes(subprocess.check_output(["adb", "-s", PAD, "exec-out", "screencap", "-p"]))
        finally:
            report["ended"] = probe(PHONE, "ended")
        assert report["ended"].get("result", {}).get("data", {}).get("effect") == "ended", report["ended"]
        report["connection_route_passed"] = True
        assert report["native_ui_observed"], "Native Connection receipt alone does not establish visible incoming UI; inspect the screenshot"
        report["passed"] = True
    finally:
        (OUT / "synthetic-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
