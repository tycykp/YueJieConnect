"""Real-device sharing acceptance after installing a candidate and confirming Bluetooth grants.

Runs Wi-Fi then cellular sharing from the module's visible button. Restores the phone's
Wi-Fi switch in finally; retains failures and never interprets command acceptance as success.
"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from debug_control import command

ROOT = Path(__file__).resolve().parents[1]
PAD, PHONE = "b59f8656", "3B161Q00YN200000"
PKG = "io.github.yuejie.connect"
OUT = ROOT / "build/network-sharing/live" / uuid.uuid4().hex
OUT.mkdir(parents=True)
record = {"scenarios": [], "passed": False}


def adb(serial, *args, binary=False):
    run = subprocess.run(["adb", "-s", serial, *args], capture_output=True, timeout=30)
    if run.returncode:
        raise RuntimeError(run.stderr.decode("utf-8", "replace")[:300])
    return run.stdout if binary else run.stdout.decode("utf-8", "replace")


def save():
    (OUT / "result.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")


def states():
    return {s: command(s, {"op": "bridge_status"}) for s in (PAD, PHONE)}


def click(prefix):
    adb(PAD, "shell", "am", "start", "-W", "-n", PKG + "/.MainActivity")
    time.sleep(1)
    for attempt in range(3):
        path="/sdcard/yj-network-ui-"+uuid.uuid4().hex+".xml"
        try:
            dumped=adb(PAD,"shell","uiautomator","dump",path)
            if "dumped to:" not in dumped:
                raise RuntimeError("Fresh UI hierarchy unavailable; do not act on an older dump")
            tree=ET.fromstring(adb(PAD,"shell","cat",path))
        finally:
            adb(PAD,"shell","rm","-f",path)
        for node in tree.iter("node"):
            if node.get("text", "").startswith(prefix) and node.get("enabled") == "true":
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                if y2 > y1:
                    x,y=str((x1+x2)//2),str((y1+y2)//2)
                    adb(PAD, "shell", "input", "touchscreen", "-d", "0", "swipe", x, y, x, y, "180")
                    time.sleep(1)
                    return
        adb(PAD, "shell", "input", "touchscreen", "-d", "0", "swipe", "1800", "700", "1800", "1800", "350")
    raise RuntimeError("Visible module action not found: " + prefix)


def wait_phase(phase, upstream=None):
    deadline = time.monotonic() + 48
    while True:
        rows = states()
        for row in rows.values():
            if row["bridge"]["phase"] != "active" or row["bridge"]["session"] != record["session"]:
                raise RuntimeError("Authenticated Bluetooth control session was lost")
        if all(row.get("network_share", {}).get("phase") == phase
               and (upstream is None or row["network_share"].get("upstream") == upstream)
               for row in rows.values()):
            return rows
        if time.monotonic() >= deadline:
            record["last_states"] = rows
            raise RuntimeError("Timed out waiting for bilateral " + phase)
        time.sleep(2)


def probes():
    result = {}
    for serial, package in ((PAD, "com.milink.service"), (PHONE, "com.oplus.linker")):
        adb(serial, "shell", "am", "broadcast", "-a", PKG + ".NETWORK_READINESS", "-p", package)
        log = adb(serial, "shell", "logcat", "-d", "-v", "brief", "-s", "YueJieNetworkTest:I", "*:S")
        events = []
        for line in log.splitlines():
            offset = line.find('{"event":')
            if offset >= 0:
                value = json.loads(line[offset:])
                if value.get("package") == package:
                    events.append(value)
        result[serial] = {kind: next((e for e in reversed(events) if e["event"] == kind), None)
                          for kind in ("snapshot", "availability")}
    return result


wifi_before = "enabled" in adb(PHONE, "shell", "cmd", "wifi", "status").splitlines()[0]
try:
    if not wifi_before:
        raise RuntimeError("Start with phone Wi-Fi connected; no saved network is selected by the test")
    initial = states()
    record["session"] = initial[PAD]["bridge"]["session"]
    assert record["session"] and initial[PHONE]["bridge"]["session"] == record["session"]
    for row in initial.values():
        assert row["bridge"]["phase"] == "active" and row["bridge"]["control_transport"] == "bluetooth"
        assert "network_share" in row["bridge"]["projection"]["operations"]
    apk = ROOT / "android/module/build/outputs/apk/debug/module-debug.apk"
    record["apk_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
    record["baseline"] = probes()
    for upstream in ("WIFI", "CELLULAR"):
        scenario = {"upstream": upstream, "passed": False}
        record["scenarios"].append(scenario)
        if upstream == "CELLULAR":
            adb(PHONE, "shell", "su", "-c", "svc wifi disable")
        deadline = time.monotonic() + 25
        while True:
            available = probes()[PHONE]["availability"]
            if available and available.get("provider") and available.get("upstream") == upstream:
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("Provider upstream unavailable: " + upstream)
            time.sleep(2)
        print(upstream+" starting from visible module button",flush=True)
        click("开始共享")
        scenario["active"] = wait_phase("ACTIVE", upstream)
        scenario["probes"] = probes()
        snapshots = [v["snapshot"] for v in scenario["probes"].values()]
        assert all(v["phase"] == "ACTIVE" and v["upstream"] == upstream for v in snapshots)
        assert snapshots[0]["share_id"] == snapshots[1]["share_id"]
        wifi = adb(PAD, "shell", "cmd", "wifi", "status")
        assert re.search(r'Wifi is connected to "YJ-[a-f0-9]{16}"', wifi)
        scenario["hotspot_connected"] = True
        scenario["ping"] = adb(PAD, "shell", "ping", "-c", "3", "-W", "3", "1.1.1.1")
        assert "3 received" in scenario["ping"]
        scenario["tether_route"] = "\n".join(line for line in adb(PHONE, "shell", "dumpsys", "tethering").splitlines()
                                               if "Current upstream" in line or "Upstream wanted:" in line)
        save()
        click("停止网络")
        scenario["stopped"] = wait_phase("STOPPED")
        networks = adb(PAD, "shell", "cmd", "wifi", "list-networks")
        assert not re.search(r"YJ-[a-f0-9]{16}", networks)
        assert not re.search(r'Wifi is connected to "YJ-', adb(PAD, "shell", "cmd", "wifi", "status"))
        scenario["temporary_network_removed"] = True
        scenario["passed"] = True
        save()
        print(upstream + " passed: bilateral ACTIVE, Internet packets, stop and configuration removal", flush=True)
    record["passed"] = True
except Exception as error:
    record["error"] = str(error)
    raise
finally:
    try:
        current = states()
        if any(r.get("network_share", {}).get("phase") in ("ACTIVE", "STARTING", "READY", "REQUESTED") for r in current.values()):
            click("停止网络")
            record["cleanup"] = wait_phase("STOPPED")
    except Exception as error:
        record["cleanup_error"] = str(error)
    if wifi_before:
        adb(PHONE, "shell", "su", "-c", "svc wifi enable")
    save()
    print(str(OUT / "result.json"), flush=True)
