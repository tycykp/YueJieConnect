"""Record which existing finder tests remain applicable without executing them again."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/device-finder-round2"
OUT.mkdir(parents=True, exist_ok=True)
MAIN = "android/module/src/main/java/io/github/qiangjian/connect/"
TEST = "android/module/src/test/java/io/github/qiangjian/connect/"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


reused = []
for label, name, tested in (
    ("rebind-12", "build/device-finder-rebind/verification.json", None),
    ("negative-1077", "build/device-finder-negative/verification.json",
     [MAIN + "DeviceFinderController.java", MAIN + "XiaomiFinderCodec.java",
      TEST + "DeviceFinderAdversarialCheck.java", "scripts/verify-device-finder-negative.py"]),
):
    path = ROOT / name
    prior = json.loads(path.read_text(encoding="utf-8"))
    if tested is None:
        expected = prior["source_sha256"]
    else:
        expected = dict(prior["production_sha256"])
        expected[TEST + "DeviceFinderAdversarialCheck.java"] = prior["test_sha256"]
        expected["scripts/verify-device-finder-negative.py"] = prior["script_sha256"]
        expected = {key.replace("\\", "/"): value for key, value in expected.items()}
        expected = {key: expected[key] for key in tested}
    matches = {key: sha(ROOT / key) for key in expected}
    assert matches == expected, (label, matches)
    assert all(result["exit_code"] == 0 for result in prior["commands"]), label
    reused.append(dict(label=label, evidence=name, evidence_sha256=sha(path), source_sha256=matches,
                       result="prior pass reused; test/production inputs unchanged; commands not rerun"))

base = ROOT / "build/device-finder-validation/verification.json"
record = json.loads(base.read_text(encoding="utf-8"))
baseline_hashes = {key.replace("\\", "/"): value for key, value in record["source_sha256"].items()}
for name in ("DeviceFinderController.java", "XiaomiFinderCodec.java"):
    key = MAIN + name
    assert baseline_hashes[key] == sha(ROOT / key)
assert baseline_hashes[TEST + "DeviceFinderLogicTest.java"] == sha(ROOT / (TEST + "DeviceFinderLogicTest.java"))
assert all(result["exit_code"] == 0 for result in record["commands"])
reused.append(dict(label="baseline-49-and-xiaomi-dex-11", evidence=str(base.relative_to(ROOT)),
                   evidence_sha256=sha(base), result="verify-device-finder.py reused pure JVM commands and original DEX; only changed Android adapter recompiled"))
sources = sorted(path for pattern in ("DeviceFinder*.java", "XiaomiFinder*.java", "OplusFinder*.java")
                 for path in (ROOT / MAIN).glob(pattern))
result = dict(
    date="2026-11-08", thread="01a1bba3-1392-7d31-90d9-3f60693d17aa",
    command="python scripts/audit-device-finder-reuse.py", reused=reused,
    source_sha256={str(path.relative_to(ROOT)): sha(path) for path in [*sources, Path(__file__)]},
    changed_scope="XiaomiFinderAdapter release/reconnect race covered by its new 13-assertion harness; controller/codec/receiver code unchanged",
    apk="await stable shared batch; no APK/device conclusion", locks="none held; no adb/device operation",
)
(OUT / "source-reuse.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print("PASS source reuse audit: prior 12 rebind + 49 baseline + 1077 negative assertions retained without rerun")
