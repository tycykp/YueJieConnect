"""Aggregate existing source-specific evidence; never rerun tests/builds or touch a device."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/network-sharing"
MAIN = Path("android/module/src/main/java/io/github/qiangjian/connect")
TEST = Path("android/module/src/test/java/io/github/qiangjian/connect")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(name):
    return json.loads((OUT / name).read_text(encoding="utf-8-sig"))


def normalized_sources(record):
    return {path.replace("\\", "/"): digest.lower() for path, digest in record["sources"].items()}


matrix = [
    ("NetworkShareRecoveryTest", "verification-NetworkShareRecoveryTest.json", 19,
     ["NetworkShareRecovery", "NetworkShareRecoveryPlan", "NetworkShareSession"]),
    ("NetworkShareControllerTest", "verification-NetworkShareControllerTest.json", 16,
     ["NetworkShareController", "NetworkShareSession"]),
    ("NetworkShareSessionTest", "verification-NetworkShareRecoveryTest-NetworkShareSessionTest-NetworkShareControllerTest.json", 9,
     ["NetworkShareSession"]),
    ("NetworkShareWireTest", "verification.json", 6, ["NetworkShareWire"]),
]
records = []
for test_class, filename, count, dependencies in matrix:
    evidence = read(filename)
    assert all(run["returncode"] == 0 for run in evidence["runs"]), filename
    assert any("io.github.qiangjian.connect." + test_class in run["command"] for run in evidence["runs"]), filename
    dependencies = [MAIN / (name + ".java") for name in dependencies] + [TEST / (test_class + ".java")]
    hashes = normalized_sources(evidence)
    assert all(hashes[str(path).replace("\\", "/")] == sha(ROOT / path) for path in dependencies), filename + " source drift"
    assert len(re.findall(r"@Test\b", (ROOT / dependencies[-1]).read_text(encoding="utf-8"))) == count
    records.append({"test_class": test_class, "distinct_test_count": count, "evidence": str((OUT / filename).relative_to(ROOT)),
                    "matched_sources": {str(path): sha(ROOT / path) for path in dependencies},
                    "scope": "synthetic JVM decisions; Android/OEM execution not exercised"})

sources = sorted((ROOT / MAIN).glob("NetworkShare*.java")) + sorted((ROOT / MAIN).glob("XiaomiNetwork*.java")) + sorted((ROOT / MAIN).glob("OplusNetwork*.java"))
compiled = normalized_sources(read("verification-NetworkShareRecoveryTest.json"))
assert all(compiled[str(path.relative_to(ROOT)).replace("\\", "/")] == sha(path) for path in sources), "latest standalone compile drift"
tests = sorted((ROOT / TEST).glob("NetworkShare*Test.java"))
scripts = [ROOT / "scripts" / filename for filename in ["verify-network-sharing.py", "verify-network-samples.py", "verify-network-permissions.ps1", "record-network-round2.py"]]
lint = read("lint-permissions-evidence.json")
lint_matches = lint["endpoint_sha256"].lower() == sha(ROOT / MAIN / "NetworkShareEndpoint.java")
lint_passed = lint["exit_code"] == 0 and lint.get("report_current", False) and lint_matches
apk = ROOT / "android/module/build/outputs/apk/debug/module-debug.apk"
apk_record = {"path": str(apk), "tested": False}
if apk.exists():
    digest = sha(apk)
    aapt = Path.home() / "AppData/Local/Android/Sdk/build-tools/37.0.0/aapt2.exe"
    result = subprocess.run([str(aapt), "dump", "badging", str(apk)], capture_output=True, encoding="utf-8", errors="replace", check=True)
    assert sha(apk) == digest, "APK changed during read-only inventory"
    package = result.stdout.splitlines()[0]
    apk_record.update({"sha256": digest, "package_badging": package})
record = {
    "utc": datetime.now(timezone.utc).isoformat(), "executor": "network-sharing/01a1bba3-0cc5-7be3-9aad-37330bfc511f",
    "scope": "source-specific reuse and latest standalone compile; no APK assembly/install, ADB or network change",
    "production_sources": {str(path.relative_to(ROOT)): sha(path) for path in sources},
    "tests_and_scripts": {str(path.relative_to(ROOT)): sha(path) for path in tests + scripts},
    "tests": records, "distinct_jvm_test_count": sum(test["distinct_test_count"] for test in records),
    "compile_evidence": "build/network-sharing/verification-NetworkShareRecoveryTest.json",
    "lint": {"passed_for_current_endpoint": lint_passed, "evidence": lint, "endpoint_matches": lint_matches},
    "existing_apk_not_tested": apk_record,
    "remaining": ["shared typed operation/grant and binding installation", "real authenticated control independent of switched WLAN",
                  "OEM callbacks/creator fields and process-death recovery device validation", "bidirectional native initiation and real join/restore on unmetered synthetic conditions"],
}
(OUT / "round2-ledger.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"Evidence verified: {len(sources)} production sources compiled; {record['distinct_jvm_test_count']} distinct JVM cases (existing runs reused)")
print(f"Current Endpoint MissingPermission lint: {lint_passed}; no device operations")
print("Evidence: build/network-sharing/round2-ledger.json")
