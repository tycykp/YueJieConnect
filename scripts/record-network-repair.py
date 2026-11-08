"""Freeze and audit NET-001's existing targeted evidence; no builds, tests or device access."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/network-sharing"
DEST = OUT / "functional-repair-20261109"
MAIN = Path("android/module/src/main/java/io/github/qiangjian/connect")
TEST = Path("android/module/src/test/java/io/github/qiangjian/connect")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def hashes(record):
    return {p.replace("\\", "/"): digest.lower() for p, digest in record["sources"].items()}


def frozen(source, name):
    target = DEST / name
    data = source.read_bytes()
    if target.exists():
        assert target.read_bytes() == data, f"frozen evidence already exists with other data: {target}"
    else:
        with target.open("xb") as stream:
            stream.write(data)
    return target


DEST.mkdir(parents=True, exist_ok=True)
production = sorted((ROOT / MAIN).glob("NetworkShare*.java")) + sorted((ROOT / MAIN).glob("XiaomiNetwork*.java")) + sorted((ROOT / MAIN).glob("OplusNetwork*.java"))
new_runs = []
for filename, name, classes, dependencies in [
    # The first run's RPC scope was superseded by the atomic dispatch-lease change.
    # Preserve the record, but reuse only its unchanged Session class (10 cases).
    ("verification-NetworkShareSessionTest-NetworkShareRpcTest.json", "session-rpc.json", ["NetworkShareSessionTest"], ["NetworkShareSession"]),
    ("verification-NetworkShareRecoveryTest.json", "recovery.json", ["NetworkShareRecoveryTest"], ["NetworkShareRecovery", "NetworkShareRecoveryPlan", "NetworkShareSession"]),
    ("verification-NetworkShareRpcTest.json", "rpc.json", ["NetworkShareRpcTest"], ["NetworkShareRpc", "NetworkShareWire", "NetworkShareEndpoint", "NetworkShareSession"]),
]:
    source = OUT / filename
    record = load(source)
    assert set(classes).issubset(record["executed_test_classes"]), filename
    assert len(record["runs"]) == 2 and all(run["returncode"] == 0 for run in record["runs"]), filename
    inputs = hashes(record)
    assert all(inputs[(MAIN / (p + ".java")).as_posix()] == sha(ROOT / MAIN / (p + ".java")) for p in dependencies), filename + " source drift"
    if name == "rpc.json":
        assert all(inputs[p.relative_to(ROOT).as_posix()] == sha(p) for p in production), filename + " latest compile source drift"
    count = 0
    for test_class in classes:
        test = ROOT / TEST / (test_class + ".java")
        assert inputs[test.relative_to(ROOT).as_posix()] == sha(test), str(test)
        count += len(re.findall(r"@Test\b", test.read_text(encoding="utf-8")))
    run_count = re.search(r"OK \((\d+) tests\)", record["runs"][-1]["stdout"])
    assert run_count and int(run_count[1]) >= count, filename
    target = frozen(source, name)
    new_runs.append({"evidence": target.relative_to(ROOT).as_posix(), "evidence_sha256": sha(target),
                     "classes_credited_at_current_sha": classes, "count": count,
                     "all_classes_executed_in_original_run": record["executed_test_classes"],
                     "matched_dependencies": {p: inputs[(MAIN / (p + ".java")).as_posix()] for p in dependencies},
                     "original_time_utc": record["time_utc"]})

# Reuse only unchanged code in the old tests' actual exercised scope. Controller/Wire
# fixtures use Session records/enums, not the modified resource allocator request().
# This is not a claim that their OLD Session implementation SHA tested the new method.
reuse = []
for filename, test_class, production_names in [
    ("verification-NetworkShareControllerTest.json", "NetworkShareControllerTest", ["NetworkShareController", "NetworkSharePolicy"]),
    ("verification.json", "NetworkShareWireTest", ["NetworkShareWire"]),
]:
    source = OUT / filename
    record = load(source)
    assert all(run["returncode"] == 0 for run in record["runs"]), filename
    assert any("io.github.qiangjian.connect." + test_class in run["command"] for run in record["runs"]), filename
    inputs = hashes(record)
    dependencies = [ROOT / MAIN / (name + ".java") for name in production_names] + [ROOT / TEST / (test_class + ".java")]
    assert all(inputs[p.relative_to(ROOT).as_posix()] == sha(p) for p in dependencies), filename + " reuse source drift"
    test_source = dependencies[-1].read_text(encoding="utf-8")
    assert not re.search(r"new\s+NetworkShareSession\s*\(", test_source), test_class + " uses changed allocator"
    target = frozen(source, "reused-" + test_class + ".json")
    reuse.append({"test_class": test_class, "evidence": target.relative_to(ROOT).as_posix(), "evidence_sha256": sha(target),
                  "unchanged_exercised_sources": {p.relative_to(ROOT).as_posix(): sha(p) for p in dependencies},
                  "limit": "Session record/enum API unchanged; its modified request() was not exercised by this historical fixture"})

lint = load(OUT / "lint-permissions-evidence.json")
assert lint["exit_code"] == 0 and lint["report_current"] and lint["unchanged_during_check"]
assert lint["endpoint_sha256"].lower() == sha(ROOT / MAIN / "NetworkShareEndpoint.java")
baseline_apk = ROOT / "build/native-pairing-validation/module-v19-round2-release-candidate.apk"
assert sha(baseline_apk) == "2bac018151aab2c48c2e76681d6f4ee43034bbc375e2880288264913cff112e7"
scripts = [Path(__file__), ROOT / "scripts/verify-network-sharing.py"]
ledger = {
    "time_utc": datetime.now(timezone.utc).isoformat(),
    "executor": "network-sharing/01a1bbe4-e840-7143-bb3f-9684d9fcea49",
    "scope": "NET-001 independent implementation and exact targeted evidence; no APK assembly/install, ADB, pairing or WLAN/hotspot/cellular changes",
    "production_sources": {p.relative_to(ROOT).as_posix(): sha(p) for p in production},
    "test_and_script_sources": {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted((ROOT / TEST).glob("NetworkShare*Test.java")) + scripts},
    "new_targeted_runs": new_runs, "new_targeted_test_count": sum(run["count"] for run in new_runs),
    "latest_all_network_sdk37_compile": "build/network-sharing/functional-repair-20261109/rpc.json",
    "historical_scope_reuse": reuse,
    "endpoint_permission_lint_reuse": {"evidence": "build/network-sharing/lint-permissions-evidence.json", "sha256": sha(OUT / "lint-permissions-evidence.json"), "endpoint_sha256": lint["endpoint_sha256"].lower()},
    "baseline_apk_not_validating_repair": {"version_code": 19, "version_name": "0.5.3-business-v6", "path": baseline_apk.relative_to(ROOT).as_posix(), "sha256": sha(baseline_apk)},
    "remaining": ["public typed operation/grant/cancellation binding", "actual authenticated bearer surviving consumer WLAN replacement", "native binding and OEM capability/property publication", "single coordinated device window after integration for real state/owner death/restoration"],
    "locks_held_by_this_thread": [], "device_operations": [],
}
(DEST / "ledger.json").write_text(json.dumps(ledger, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"Frozen {ledger['new_targeted_test_count']} distinct targeted JVM cases at current scoped SHAs; {len(production)} production inputs match the latest SDK37 compile.")
print("Reused unchanged Controller/Wire scope and exact Endpoint permission evidence; no tests rerun.")
print("Evidence: " + (DEST / "ledger.json").relative_to(ROOT).as_posix())
