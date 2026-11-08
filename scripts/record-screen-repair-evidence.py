"""Record exact screen/input repair evidence; never invokes tests, builds or devices."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
out = root / "build/screen-repair-20261109"
out.mkdir(parents=True, exist_ok=True)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def relative(path):
    return str(path.relative_to(root)).replace("\\", "/")


def read_manifest(folder):
    # Preserve earlier Windows PowerShell 5.1 array-wrapper manifests as historical evidence.
    def rows(value):
        if isinstance(value, list):
            for item in value:
                yield from rows(item)
        elif "Path" in value:
            yield value
        else:
            yield from rows(value["value"])
    return list(rows(json.loads((folder / "source-sha256.json").read_text(encoding="utf-8-sig"))))


java = "android/module/src/main/java/io/github/qiangjian/connect/"
test = "android/module/src/test/java/io/github/qiangjian/connect/"
checks = [
    ("native-session-final", "build/screen-repair-consent-assembly-validation", 17,
     [java + name + ".java" for name in ["ScreenCastNativeSession", "ScreenCastSession", "ScreenCastFormat", "ScreenCastPacket", "ScreenCastFrameQueue"]]
     + [test + "ScreenCastNativeSessionTest.java"],
     "powershell -NoProfile -ExecutionPolicy Bypass -Command \"& ./scripts/test-screen-input.ps1 -OutputDirectory build/screen-repair-consent-assembly-validation -TestClasses @('ScreenCastNativeSessionTest','OplusScreenSessionLeaseTest')\""),
    ("input-receiver-final", "build/screen-repair-input-close-validation", 9,
     [java + name + ".java" for name in ["RemoteInputReceiver", "RemoteInputSender", "RemoteInputSession", "XiaomiScreenInputCodec", "RemoteInputDesktopSession"]]
     + [test + "RemoteInputReceiverTest.java"],
     "powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-screen-input-assembly.ps1 -OutputDirectory build/screen-repair-input-close-validation -TestClasses RemoteInputReceiverTest"),
    ("oplus-native-target", "build/screen-repair-input-assembly-validation", 13,
     [java + name + ".java" for name in ["RemoteInputOplusTarget", "OplusScreenInputAdapter", "RemoteInputDesktopExecutor", "RemoteInputSession", "RemoteInputImeTarget"]]
     + [test + "RemoteInputOplusTargetTest.java"],
     "powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-screen-input-assembly.ps1"),
    ("oplus-release", "build/screen-repair-final-release-validation", 7,
     [java + name + ".java" for name in ["OplusScreenSourceAdapter", "OplusScreenRequestAdapter", "ScreenCastNativeConsent"]]
     + [test + "OplusScreenReleaseTest.java"],
     "powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-screen-input-assembly.ps1 -OutputDirectory build/screen-repair-final-release-validation -TestClasses OplusScreenReleaseTest"),
    ("native-source-manager", "build/screen-repair-source-manager-validation", 9,
     [java + "OplusScreenSessionLease.java", test + "OplusScreenSessionLeaseTest.java"],
     "powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-screen-input.ps1 -OutputDirectory build/screen-repair-source-manager-validation -TestClasses OplusScreenSessionLeaseTest"),
    ("screen-bridge-and-native-focus", "build/screen-repair-bridge-validation", 10,
     [java + name + ".java" for name in ["ScreenCastBridge", "OplusScreenFocusAdapter", "ScreenCastNativeSession", "ScreenCastSession"]]
     + [test + "ScreenCastBridgeTest.java", test + "OplusScreenFocusAdapterTest.java"],
     "powershell -NoProfile -ExecutionPolicy Bypass -Command \"& ./scripts/test-screen-input-assembly.ps1 -OutputDirectory build/screen-repair-bridge-validation -TestClasses @('ScreenCastBridgeTest','OplusScreenFocusAdapterTest')\""),
]
records = []
for name, directory, count, dependencies, command in checks:
    folder = root / directory
    manifest = {str(Path(row["Path"]).resolve()).casefold(): row["Hash"].lower() for row in read_manifest(folder)}
    log = (folder / "tests.log").read_bytes()
    assert f"OK ({count} tests)" in log.decode("utf-16" if log.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig"), name
    inputs = []
    for filename in dependencies:
        path = root / filename
        current = sha(path)
        assert manifest[str(path.resolve()).casefold()] == current, (name, filename)
        inputs.append({"path": filename, "sha256": current})
    records.append({"name": name, "command": command, "tests_in_log": count, "status": "passed",
                    "evidence": directory, "unchanged_tested_sources": inputs})

# Last compilation covered every current screen source/test. It is distinct from test reuse.
compiled_manifest = read_manifest(root / "build/screen-repair-final-release-validation")
for row in compiled_manifest:
    if Path(row["Path"]).suffix == ".java":
        assert sha(Path(row["Path"])) == row["Hash"].lower(), row["Path"]

changed = [java + name + ".java" for name in [
    "ScreenCastNativeSession", "RemoteInputReceiver", "RemoteInputOplusTarget", "OplusScreenInputAdapter",
    "OplusScreenSourceAdapter", "OplusScreenRequestAdapter", "XiaomiScreenSinkAdapter", "XiaomiScreenRequestAdapter"]]
changed += [java + name + ".java" for name in ["OplusScreenSessionLease", "OplusScreenFocusAdapter", "ScreenCastBridge"]]
changed += [test + name + ".java" for name in ["ScreenCastNativeSessionTest", "RemoteInputReceiverTest", "RemoteInputOplusTargetTest", "OplusScreenReleaseTest"]]
changed += [test + name + ".java" for name in ["OplusScreenSessionLeaseTest", "OplusScreenFocusAdapterTest", "ScreenCastBridgeTest"]]
changed += ["transport/src/media_runtime.rs", "scripts/test-screen-input.ps1", "scripts/test-screen-input-assembly.ps1", "scripts/record-screen-repair-evidence.py",
            "scripts/test-screen-media-runtime.ps1", "scripts/verify-screen-oplus-input-route.py"]
runtime_folder = root / "build/screen-media-runtime-validation"
runtime_manifest = read_manifest(runtime_folder)
for row in runtime_manifest:
    assert sha(Path(row["Path"])) == row["Hash"].lower(), row["Path"]
assert "11 passed; 0 failed" in (runtime_folder / "tests.log").read_text(encoding="utf-8-sig")
assert "13 filtered out" in (runtime_folder / "tests.log").read_text(encoding="utf-8-sig")
for log in ["clippy.log", "android-check.log"]:
    assert "Finished" in (runtime_folder / log).read_text(encoding="utf-8-sig")
native_focus = json.loads((root / "build/screen-repair-native-focus-validation/native-signatures.json").read_text(encoding="utf-8"))
assert sha(root / "scripts/verify-screen-oplus-input-route.py") == native_focus["script_sha256"]
ledger = {
    "task": "screen-input", "round": "functional-repair-after-single-sweep-20261108",
    "thread_id": "01a1bbe4-e0ec-7f83-b2d3-be0ba57e005f", "date": "2026-11-09",
    "status": "continuing_until_native_screen_and_input_usable_shared_code21_integration_in_progress",
    "new_apk": None, "device_actions": [], "locks_acquired": [],
    "baseline_apk": {"version_code": 19, "version_name": "0.5.3-business-v6",
                     "sha256": "2bac018151aab2c48c2e76681d6f4ee43034bbc375e2880288264913cff112e7",
                     "scope": "installed test baseline only; does not contain this repair"},
    "unique_current_java_test_cases": 48, "checks": records,
    "new_media_runtime": {"command": "powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-screen-media-runtime.ps1 -CheckTools",
                          "evidence": "build/screen-media-runtime-validation", "tests": 11,
                          "old_channel_tests_filtered_not_rerun": 13, "clippy": "passed", "aarch64_linux_android_check": "passed"},
    "new_oem_input_route_check": {"command": "python scripts/verify-screen-oplus-input-route.py", "classes": 5,
                                 "evidence": "build/screen-repair-native-focus-validation/native-signatures.json"},
    "reuse_notes": [
        "Initial 13-case log contains 9 receiver cases superseded by the final 9-case receiver log and 4 unchanged Oplus target cases; count 4 only from that log.",
        "Native-session-final log has 9 current native-session cases and 8 earlier SourceLease cases; SourceLease's final 9-case log supersedes those 8, so count 9 only from that 17-case log.",
        "Source release now has 7 final cases including identity tombstones and expired lookup, rerun only after the native-source callback interface changed.",
        "Receiver Target method signatures stayed unchanged when close moved the immediate fence to the caller; actual Oplus target's 4 direct assembly cases remain applicable.",
        "Final Java17/Android37/libxposed102 compile covered 30 main classes and 17 test classes; per-source current SHA verified against the final compile manifest.",
        "Assembly script's final metadata-only correction flattens a PowerShell 5.1 array wrapper; historical manifests/logs retained and normalized read-only. Test commands/Java sources unchanged by that correction.",
        "Unchanged codec/media/IME baselines were not rerun. No OEM hook, authenticated media/JNI/PFD, native window, remote visible input or drag device acceptance is asserted.",
        "Receiver termination means its assembly worker ended and native cleanup was dispatched, not an OEM release acknowledgement.",
    ],
    "sources": [{"path": path, "sha256": sha(root / path)} for path in changed],
    "stable_media_channel": {"path": "transport/src/media_channel.rs", "sha256": sha(root / "transport/src/media_channel.rs"), "changed": False},
    "remaining": ["authenticated control negotiation and media JNI/PFD", "real OEM peer/request/stream mapping and delegate installation",
                  "actual display/target UID native input focus and independent input grant", "native window first frame and remote input/drag acceptance",
                  "reverse video, resize/new-stream renegotiation and audio"],
}
(out / "ledger-continued.json").write_text(json.dumps(ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("PASS: exact hashes matched; 48 Java cases, 11 new Rust runtime cases and PAD focus DEX evidence recorded; no device/APK claim")
