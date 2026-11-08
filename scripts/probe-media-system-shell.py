"""Stock CLI usage only. Does not enumerate sessions or dispatch any media/input command."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--expected-lock", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    lock = root / "build/coordination-locks/devices.lock"
    assert lock.read_text(encoding="utf-8-sig") == args.expected_lock
    records = []
    try:
        for serial in ["b59f8656", "3B161Q00YN200000"]:
            # This device's legacy parser calls "help" unknown; no args prints the real usage.
            command = ["adb", "-s", serial, "shell", "cmd", "media_session"]
            result = subprocess.run(command, capture_output=True, timeout=15)
            content = result.stdout + result.stderr
            path = args.output_dir / (serial + "-usage.txt")
            path.write_bytes(content)
            record = {"serial": serial, "command": command, "exit_code": result.returncode,
                      "path": str(path), "sha256": hashlib.sha256(content).hexdigest(),
                      "usage_observed": b"monitor" in content and b"list-sessions" in content}
            records.append(record)
            assert record["usage_observed"], "No stock media usage; no active command was executed"
    finally:
        (args.output_dir / "usage-evidence.json").write_text(json.dumps({"records": records,
                "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                "scope": "Read-only legacy CLI usage, no user session enumeration/control/UI"}, indent=2), encoding="utf-8")
    print("Both stock media CLI usage interfaces captured; no active media command or UI operation.")


if __name__ == "__main__":
    main()
