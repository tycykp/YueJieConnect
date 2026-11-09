"""Interactive, single network-sharing device window; PS wrapper owns both-device lease.

Does not install, pair, grant metered use, invoke business RPCs, or change Wi-Fi via shell.
Business actions use observed OEM UI. One source/candidate-specific run retains all failures.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
from debug_control import command

ROOT = Path(__file__).resolve().parents[1]
DEVICES = {"b59f8656": "com.milink.service", "3B161Q00YN200000": "com.oplus.linker"}
TASK = "01a1bbe4-e840-7143-bb3f-9684d9fcea49"
PKG = "io.github.yuejie.connect"
ACTION = PKG + ".NETWORK_READINESS"
parser = argparse.ArgumentParser()
parser.add_argument("--apk", type=Path, required=True)
parser.add_argument("--build-ledger", type=Path, required=True)
parser.add_argument("--build-inputs", type=Path, required=True)
parser.add_argument("--expected-session", required=True)
parser.add_argument("--network-operation", required=True)
parser.add_argument("--lease-id", required=True)
args = parser.parse_args()
if not re.fullmatch("[a-f0-9]{64}", args.expected_session): raise ValueError("Expected current authenticated session required")
if not re.fullmatch("[a-z_]{1,48}", args.network_operation): raise ValueError("Actual typed operation name required")
lock = (ROOT / "build/coordination-locks/devices.lock").read_text(encoding="utf-8-sig")
if TASK not in lock or args.lease_id not in lock: raise RuntimeError("Our exclusive both-device lease is required")
OUT = ROOT / "build/network-sharing/device" / uuid.uuid4().hex
OUT.mkdir(parents=True)
record = {"utc": datetime.now(timezone.utc).isoformat(), "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
          "session": args.expected_session, "network_operation": args.network_operation, "steps": [],
          "business_verdict": "pending actual native actions/results", "device_mutation_attempted": False}
labels = set()
last_probe = {}
screenshots = {}


def save():
    (OUT / "result.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")


def adb(serial, *parts, binary=False):
    if serial not in DEVICES: raise ValueError("Unknown test device")
    run = subprocess.run(["adb", "-s", serial, *map(str, parts)], capture_output=True, timeout=30)
    if run.returncode: raise RuntimeError("ADB command failed; no state-changing retry")
    return run.stdout if binary else run.stdout.decode("utf-8", "replace")


def events(serial):
    log = adb(serial, "shell", "logcat", "-d", "-v", "brief", "-s", "YueJieNetworkTest:I", "*:S")
    parsed = []
    for line in log.splitlines():
        offset = line.find('{"event":')
        if offset < 0: continue
        logger = re.search(r"YueJieNetworkTest\(\s*(\d+)\)", line[:offset])
        if not logger: continue
        try: value = json.loads(line[offset:])
        except json.JSONDecodeError: continue
        if value.get("package") == DEVICES[serial] and value.get("pid") == int(logger[1]): parsed.append(value)
    return parsed


def snapshot(serial):
    before = events(serial)
    markers = {(e["pid"], e["event"]): e["elapsed_ms"] for e in before}
    adb(serial, "shell", "am", "broadcast", "-a", ACTION, "-p", DEVICES[serial])
    deadline = time.monotonic() + 5
    while True:
        current = events(serial)
        fresh = [e for e in current if e["event"] in ("snapshot", "availability")
                 and e["elapsed_ms"] > markers.get((e["pid"], e["event"]), -1)]
        owners = {}
        for event in fresh: owners.setdefault(event["pid"], {})[event["event"]] = event
        owners = {pid: row for pid, row in owners.items() if set(row) == {"snapshot", "availability"}}
        if len(owners) == 1:
            row = next(iter(owners.values())); last_probe[serial] = row
            return row
        if time.monotonic() >= deadline: raise RuntimeError("No unique live OEM endpoint snapshot; no toggle/kill permitted")
        time.sleep(.25)


def states():
    rows = {}
    for serial in DEVICES:
        state = command(serial, {"op": "bridge_status"})["bridge"]
        projection = state.get("projection") or {}
        rows[serial] = {"phase": state.get("phase"), "session": state.get("session"),
                        "projection_id": projection.get("id"), "operations": projection.get("operations", [])}
    return rows


def verify_candidate():
    metadata = json.loads(args.build_ledger.read_text(encoding="utf-8-sig"))
    inputs = json.loads(args.build_inputs.read_text(encoding="utf-8-sig"))
    digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    if metadata["sha256"] != digest: raise RuntimeError("Stable candidate APK digest mismatch")
    revision = metadata["runtime_input_sha"]
    if not re.fullmatch("[a-f0-9]{64}", revision): raise RuntimeError("Runtime input proof required")
    required = list((ROOT / "android/module/src/main/java/io/github/yuejie/connect").glob("NetworkShare*.java"))
    required += [ROOT / "android/module/src/main/java/io/github/yuejie/connect" / name for name in ("XiaomiNetworkAdapter.java", "OplusNetworkAdapter.java")]
    required += [ROOT / "android/module/src/debug/java/io/github/yuejie/connect/NetworkShareNativeProbe.java"]
    required += [ROOT / p for p in ["transport/src/stable_channel.rs", "transport/src/bridge.rs",
                 "native/src/lib.rs", "core/src/gateway.rs", "android/module/src/main/AndroidManifest.xml",
                 *["android/module/src/main/java/io/github/yuejie/connect/" + p + ".java"
                   for p in ("BridgeClient", "BridgeService", "DiscoveryModule", "MainActivity")]]]
    for source in required:
        if inputs.get(source.relative_to(ROOT).as_posix()) != hashlib.sha256(source.read_bytes()).hexdigest():
            raise RuntimeError("Candidate does not contain current network source/probe: " + source.name)
    record.update(apk=str(args.apk.resolve()), apk_sha256=digest, runtime_input_sha=revision,
                  build_ledger=str(args.build_ledger.resolve()), build_inputs=str(args.build_inputs.resolve()),
                  network_source_sha256={s.relative_to(ROOT).as_posix(): hashlib.sha256(s.read_bytes()).hexdigest() for s in required})
    aapt = Path.home() / "AppData/Local/Android/Sdk/build-tools/37.0.0/aapt2.exe"
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(args.apk)], timeout=20).decode("utf-8", "replace").splitlines()[0]
    version = re.search(r"name='io.github.yuejie.connect' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not version: raise RuntimeError("Unexpected candidate package/version")
    record.update(apk_version_code=int(version[1]), apk_version_name=version[2])
    for serial in DEVICES:
        installed = adb(serial, "shell", "pm", "path", PKG).strip()
        if not re.fullmatch(r"package:/data/app/[A-Za-z0-9_=+./~-]+\.apk", installed): raise RuntimeError("Unexpected installed package path")
        if adb(serial, "shell", "sha256sum", installed[8:]).split()[0] != digest: raise RuntimeError("Device APK differs from accepted candidate")


def preflight():
    verify_candidate()
    current = states()
    if any(row["phase"] != "active" or row["session"] != args.expected_session or args.network_operation not in row["operations"] for row in current.values()):
        raise RuntimeError("Current bilateral session/grant/typed operation is missing; no native mutation permitted")
    probes = {s: snapshot(s) for s in DEVICES}
    runtime = {s: command(s, {"op": "bridge_runtime_status"}) for s in DEVICES}
    for serial in DEVICES:
        rows = runtime[serial].get("clients", [])
        pid = probes[serial]["snapshot"]["pid"]
        if runtime[serial].get("expected_revision") != record["runtime_input_sha"] or not any(c.get("pid") == pid and c.get("matches") and c.get("revision") == record["runtime_input_sha"] for c in rows):
            raise RuntimeError("Actual OEM network executor runtime is not attested at candidate revision")
        if probes[serial]["snapshot"]["phase"] not in ("empty", "STOPPED", "FAILED"):
            raise RuntimeError("Pre-existing network resource/recovery; do not replace it")
    provider = probes["3B161Q00YN200000"]["availability"]
    consumer = probes["b59f8656"]["availability"]
    if not provider.get("provider") or provider.get("upstream") != "WIFI" or provider.get("metered") or not consumer.get("consumer"):
        raise RuntimeError("Unmetered real Wi-Fi sharing preconditions not satisfied; no cellular fallback test")
    record["preflight"] = {"states": current, "probes": probes, "runtime": runtime}


def launch(serial):
    component = "com.milink.service/com.miui.circulate.world.CirculateWorldActivity" if serial == "b59f8656" else "com.oplus.linker/.synergy.ui.activity.ConnectPCSettingsActivity"
    return adb(serial, "shell", "su", "-c", "am start -W -n " + component)


def current_native(serial):
    focus = adb(serial, "shell", "dumpsys", "window", "windows")
    return any(DEVICES[serial] + "/" in line for line in focus.splitlines() if "mCurrentFocus=" in line)


def kill_owner(serial):
    probes = {s: snapshot(s)["snapshot"] for s in DEVICES}
    here = probes[serial]
    if any(v.get("phase") != "ACTIVE" or v.get("upstream") != "WIFI" or v.get("session") != args.expected_session or v.get("share_id") != here.get("share_id") for v in probes.values()):
        raise RuntimeError("Owner death requires bilateral current matching ACTIVE synthetic share")
    pid = here["pid"]
    if type(pid) is not int or pid <= 1: raise RuntimeError("Invalid native owner PID")
    native = adb(serial, "shell", "su", "-c", "cat /proc/" + str(pid) + "/cmdline").split("\0")[0]
    if native != DEVICES[serial] and not native.startswith(DEVICES[serial] + ":"):
        raise RuntimeError("PID no longer belongs to the actual OEM executor; not killed")
    record["device_mutation_attempted"] = True
    adb(serial, "shell", "su", "-c", "kill -9 " + str(pid))
    return {"before": probes, "killed_pid": pid, "process": native, "verdict": "kill issued; restoration not inferred"}


def cleanup():
    if not record["device_mutation_attempted"]: return {"action": "none; no network mutation attempted"}
    result = {}
    for serial in DEVICES:
        try:
            state = command(serial, {"op": "bridge_status"})["bridge"]
            if state.get("session") == args.expected_session:
                result[serial] = {"stop": command(serial, {"op": "bridge_stop"})}
            else: result[serial] = {"stop": "not_our_session_not_stopped"}
            # Relaunch only the OEM UI after a killed owner so its private recovery journal can run.
            launch(serial)
        except Exception as error: result[serial] = {"cleanup_error": type(error).__name__}
    deadline = time.monotonic() + 22
    while True:
        try:
            probes = {s: snapshot(s) for s in DEVICES}
            verified = all(row["snapshot"].get("phase") in ("STOPPED", "FAILED", "empty") for row in probes.values())
            result["final_probes"] = probes
            # A new empty owner has no recovered ticket: this cannot prove old-resource cleanup.
            verified = verified and all(row["snapshot"].get("phase") != "empty" for row in probes.values())
            if verified: result["restoration"] = "endpoint_verified_terminal"; return result
        except Exception as error: result["observation_error"] = type(error).__name__
        if time.monotonic() >= deadline:
            result["restoration"] = "unverified_keep_evidence_and_recovery_journal"; return result
        time.sleep(.5)


try:
    preflight(); save()
    print(json.dumps({"ready": True, "window": str(OUT)}), flush=True)
    for line in sys.stdin:
        step = {"utc": datetime.now(timezone.utc).isoformat()}; record["steps"].append(step)
        try:
            value = json.loads(line); op = value["op"]; step["op"] = op
            if op == "quit": break
            label = value["label"]
            if not re.fullmatch("[a-z0-9_-]{1,48}", label) or label in labels: raise ValueError("New evidence label required; no duplicate scenario retry")
            labels.add(label); step["label"] = label
            if op == "observe":
                step["states"] = states(); step["probes"] = {s: snapshot(s) for s in DEVICES}
            elif op == "launch": step["result"] = {s: launch(s) for s in DEVICES}
            elif op == "capture":
                serial = value["serial"]
                if not current_native(serial): raise RuntimeError("Only current OEM network UI may be captured")
                path = OUT / (serial + "-" + label + ".png")
                path.write_bytes(adb(serial, "exec-out", "screencap", "-p", binary=True)); screenshots[label] = serial
                step["screenshot"] = str(path)
            elif op == "tap":
                serial, x, y = value["serial"], value["x"], value["y"]
                if screenshots.get(value["observed_screenshot"]) != serial or not current_native(serial): raise RuntimeError("Current OEM screenshot must be inspected before its native action")
                if type(x) is not int or type(y) is not int or not 0 <= x < 4096 or not 0 <= y < 4096: raise ValueError("Invalid observed coordinate")
                record["device_mutation_attempted"] = True
                step["result"] = adb(serial, "shell", "input", "tap", x, y)
            elif op == "owner-death": step["result"] = kill_owner(value["serial"])
            else: raise ValueError("Unknown controlled operation")
            step["status"] = "command_completed_not_business_verdict"
        except Exception as error:
            step["status"] = "failed_without_retry"; step["error"] = str(error)
        save(); print(json.dumps(step, ensure_ascii=False), flush=True)
except Exception as error:
    record["failure"] = str(error); record["business_verdict"] = "blocked_without_business_retry"
    print(json.dumps({"error": str(error), "evidence": str(OUT / "result.json")}, ensure_ascii=False), flush=True)
finally:
    record["cleanup"] = cleanup(); save()
    print(json.dumps({"closed": str(OUT / "result.json")}), flush=True)
