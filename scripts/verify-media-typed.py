"""Validate only the independent media schema in an isolated Cargo workspace/target.

Does not change production Cargo manifests/lib.rs or take over shared wire integration.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "transport/src/media_control.rs"
OUT = ROOT / "build/media-controls/typed-validation"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = OUT / "Cargo.toml"
    manifest.write_text('[package]\nname = "yj-media-control-validation"\nversion = "0.0.0"\nedition = "2021"\n'
                        '[workspace]\n[lib]\npath = "lib.rs"\n'
                        '[dependencies]\nserde = { version = "=1.0.228", features = ["derive"] }\n'
                        'serde_json = "=1.0.145"\n', encoding="utf-8")
    (OUT / "lib.rs").write_text(f'#[path = r"{SOURCE.as_posix()}"]\npub mod media_control;\n', encoding="utf-8")
    inputs = {str(p.relative_to(ROOT)): sha(p) for p in [SOURCE, Path(__file__)]}
    commands = [
        ["rustfmt", "--edition", "2021", "--check", str(SOURCE)],
        ["cargo", "generate-lockfile", "--offline", "--manifest-path", str(manifest)],
        ["cargo", "test", "--locked", "--offline", "--manifest-path", str(manifest), "--target-dir", str(OUT / "target")],
        ["cargo", "clippy", "--locked", "--offline", "--all-targets", "--manifest-path", str(manifest), "--target-dir", str(OUT / "target"), "--", "-D", "warnings"],
    ]
    steps = []
    for index, command in enumerate(commands):
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
        log = OUT / f"step-{index}.txt"
        log.write_text(result.stdout + result.stderr, encoding="utf-8")
        steps.append({"command": command, "exit_code": result.returncode, "log": str(log.relative_to(ROOT)), "log_sha256": sha(log)})
        if result.returncode:
            print((result.stdout + result.stderr)[-4000:])
            break
    after = {name: sha(ROOT / name) for name in inputs}
    report = {"utc": datetime.now(timezone.utc).isoformat(), "inputs_before": inputs, "inputs_after": after,
              "input_races": [name for name in inputs if inputs[name] != after[name]], "steps": steps,
              "manifest_sha256": sha(manifest), "lock_sha256": sha(OUT / "Cargo.lock") if (OUT / "Cargo.lock").exists() else None,
              "scope": "Isolated original media_control.rs parse/receipt/postcondition tests; not shared Cargo, native wire, APK, or devices."}
    (OUT / "validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    assert len(steps) == len(commands) and all(step["exit_code"] == 0 for step in steps), "Focused media schema validation failed"
    assert not report["input_races"], report["input_races"]
    print("Independent media schema tests/Clippy passed; no shared build or device action.")


if __name__ == "__main__":
    main()
