"""Target ONE already-authorized native system session and a real silent remote fixture.

The scene file is evidence, never a grant. This script cannot create a cast, select an app,
register a capability, or confirm pairing. The production callback still enforces the actual
native scope. Caller must hold the shared device lock and arrange fixture/cast cleanup.
Never uses global dispatch, volume, list-sessions, screenshots, or an app UI launch.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import queue
import re
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
OWNER = "media-controls 01a1bbe5-051e-73a2-aa34-64939ce47da5"
DEVICES = {"b59f8656": "com.xiaomi.mirror", "3B161Q00YN200000": "com.oplus.linker"}
SCENE_FIELDS = {"controller_serial", "fixture_serial", "controller_package", "native_session_tag",
                "target_package", "fixture_active", "fixture_playback", "fixture_callbacks",
                "screen_rendered", "media_granted", "runtime_verified", "noise_session_id",
                "native_cast_stream_id", "native_cast_screen_id", "apk_sha256", "verified_at_utc"}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_scene(scene):
    assert set(scene) == SCENE_FIELDS, "Only fixed non-content scene evidence fields are accepted"
    assert scene["controller_serial"] in DEVICES and scene["fixture_serial"] in DEVICES
    assert scene["controller_serial"] != scene["fixture_serial"]
    assert scene["controller_package"] == DEVICES[scene["controller_serial"]]
    assert re.fullmatch(r"yuejie-remote-control-[0-9a-f]{32}", scene["native_session_tag"])
    assert scene["target_package"] == "io.github.yuejie.connect", "Only the fixed synthetic fixture target is permitted"
    assert scene["fixture_active"] is True and scene["fixture_playback"] == 2
    assert type(scene["fixture_callbacks"]) is int and scene["fixture_callbacks"] >= 0
    assert scene["screen_rendered"] is True and scene["media_granted"] is True and scene["runtime_verified"] is True
    assert re.fullmatch(r"[0-9a-f]{64}", scene["noise_session_id"])
    assert type(scene["native_cast_stream_id"]) is int and scene["native_cast_stream_id"] > 0
    assert type(scene["native_cast_screen_id"]) is int and scene["native_cast_screen_id"] >= 0
    assert re.fullmatch(r"[0-9a-f]{64}", scene["apk_sha256"])
    when = datetime.fromisoformat(scene["verified_at_utc"])
    assert when.tzinfo is not None and 0 <= (datetime.now(timezone.utc) - when).total_seconds() <= 45


def installed_hash(serial):
    command = ["adb", "-s", serial, "shell", "pm", "path", "io.github.yuejie.connect"]
    result = subprocess.run(command, capture_output=True, timeout=10, text=True)
    assert result.returncode == 0
    path = result.stdout.strip()
    assert re.fullmatch(r"package:/data/app/[A-Za-z0-9_=+./~-]+\.apk", path)
    result = subprocess.run(["adb", "-s", serial, "shell", "sha256sum", path[8:]], capture_output=True, timeout=10, text=True)
    assert result.returncode == 0
    digest = result.stdout.split()[0]
    assert re.fullmatch(r"[0-9a-f]{64}", digest)
    return digest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--scene", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--expected-lock", required=True)
    args = parser.parse_args()
    lock = ROOT / "build/coordination-locks/devices.lock"
    assert args.expected_lock.startswith(OWNER + " ") and lock.read_text(encoding="utf-8-sig") == args.expected_lock
    scene = json.loads(args.scene.read_text(encoding="utf-8-sig"))
    validate_scene(scene)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    record = {"utc": datetime.now(timezone.utc).isoformat(), "script_sha256": sha(Path(__file__)),
              "scene_sha256": sha(args.scene), "scene": scene, "steps": [], "result": "not_completed",
              "limits": "Only targeted stock MediaController play/pause and native system readback. Parent must collect actual remote fixture counter after closing native cast. No OEM-exclusive control/volume/seek/stop/device-finder assertion."}
    process = None
    output = queue.Queue()
    try:
        hashes = {device: installed_hash(device) for device in DEVICES}
        record["installed_sha256"] = hashes
        assert all(digest == scene["apk_sha256"] for digest in hashes.values()), "Installed artifact mismatch"
        validate_scene(scene)  # The fixed 60-second fixture must still have a fresh verified observation.
        command = ["adb", "-s", scene["controller_serial"], "shell", "cmd", "media_session", "monitor", scene["native_session_tag"]]
        record["monitor_command"] = command
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)

        def reader():
            for line in process.stdout:
                output.put(line)
            output.put(None)

        threading.Thread(target=reader, daemon=True).start()
        header, initial = "", False
        deadline = time.monotonic() + 6
        while time.monotonic() < deadline:
            try:
                line = output.get(timeout=0.2)
            except queue.Empty:
                continue
            assert line is not None, "Own native monitor was not available"
            # Never persist arbitrary metadata/log output; retain only the fixed initial state.
            if "Monitoring session" in line:
                assert scene["native_session_tag"] in line, "Unexpected native session"
                initial = True
            if "command" in line.lower() or re.search(r"\bplay\b|\bpause\b|p\[lay\]", line):
                header += line
            if initial and re.search(r"\bplay\b|p\[lay\]", header) and re.search(r"\bpause\b", header):
                break
        assert initial and re.search(r"\bplay\b|p\[lay\]", header) and re.search(r"\bpause\b", header), "Unrecognized stock monitor interface; no media command sent"
        record["own_monitor_verified"] = True
        for control, expected in [("play", 3), ("pause", 2)]:
            while True:
                try:
                    output.get_nowait()
                except queue.Empty:
                    break
            step = {"control": control, "expected_playback": expected, "sent": False, "native_readback": None}
            record["steps"].append(step)
            process.stdin.write(control + "\n"); process.stdin.flush(); step["sent"] = True
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                try:
                    line = output.get(timeout=0.2)
                except queue.Empty:
                    continue
                assert line is not None, "Native monitor closed; no command retried"
                if "onPlaybackStateChanged" not in line:
                    continue
                match = re.search(r"\bstate\s*=\s*(-?\d+)", line)
                if match and int(match.group(1)) == expected:
                    step["native_readback"] = expected
                    break
            assert step["native_readback"] == expected, "Native readback not confirmed; no state-changing retry"
        record["result"] = "native_system_play_pause_observed_remote_fixture_counter_required"
        print("Own native system session play/pause readback observed; remote fixture counter and cleanup remain required.")
    except BaseException as error:
        record["error_type"] = type(error).__name__
        raise
    finally:
        if process is not None:
            if process.poll() is None:
                try:
                    process.stdin.write("q\n"); process.stdin.flush()
                    process.wait(timeout=2)
                except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
                    process.terminate()
            record["monitor_exit"] = process.poll()
        (args.output_dir / "controller-evidence.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
