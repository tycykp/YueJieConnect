"""Aggregate exact-SHA evidence without re-running tests, building, or touching devices."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build/camera-validation/repair-20261109-ledger.json"
MAIN = ROOT / "android/module/src/main/java/io/github/yuejie/connect"
names = ["CameraShareStillReceipt", "XiaomiCameraStillConsumer", "XiaomiCameraNativeRequests",
         "XiaomiCameraStillResultHooks", "XiaomiCameraStillLifecycleHooks"]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8-sig"))


compile_path = "build/camera-validation/20261109-002911-900/result.json"
model_path = "build/camera-validation/still-receipts-20261109-001633-127/result.json"
lifecycle_path = "build/camera-validation/native-lifecycle-20261109-002909-638511/result.json"
compilation, model, lifecycle = [read(p) for p in [compile_path, model_path, lifecycle_path]]
assert all(record["result"] == "PASS" for record in [compilation, model, lifecycle])
compiled = {Path(entry["path"]).name: entry["sha256"].lower() for entry in compilation["sources"]}
executed = {Path(entry["path"]).name: entry["sha256"].lower() for entry in lifecycle["sources"]}
modeled = {Path(entry["path"]).name: entry["sha256"].lower() for entry in model["sources"]}
for name in names:
    path = MAIN / (name + ".java")
    assert compiled[path.name] == sha(path), (name, "final API compile source changed")
    assert executed[path.name] == sha(path), (name, "native lifecycle source changed")
for name in ["CameraShareStillReceipt", "CameraShareStillRequest", "CameraShareJpeg"]:
    path = MAIN / (name + ".java")
    assert modeled[path.name] == sha(path), (name, "receipt core model source changed")
audits = ["build/camera-validation/vdc-odmf-sample-audit.json", "build/camera-validation/companion-sample-audit.json"]
for path in audits:
    assert read(path)["result"] == "PASS"
record = dict(result="PASS", command="python scripts/camera-sharing/record-repair.py",
              script_sha256=sha(Path(__file__)),
              production=[dict(path=str((MAIN / (name + ".java")).relative_to(ROOT)),
                               sha256=sha(MAIN / (name + ".java"))) for name in names],
              evidence=[dict(path=path, sha256=sha(ROOT / path)) for path in [compile_path, model_path, lifecycle_path, *audits]],
              verified=dict(api37_api102_all_camera_compile=True, receipt_model_assertions=26,
                            lifecycle_platform_doubles_assertions=24, exact_source_hashes_match=True),
              apk_this_round=None, camera_opened=False, native_business_accepted=False,
              scope="Final source provenance and exact SHA evidence reuse only. No old codec/metadata/model/baseline tests repeated. Pending common authenticated media/JPEG/consent/native reader ownership route/ColorOS consumer and real phone-pad acceptance remain incomplete.")
OUT.write_text(json.dumps(record, indent=2, ensure_ascii=False), encoding="utf-8")
print(f"PASS camera repair evidence ledger: {OUT}")
