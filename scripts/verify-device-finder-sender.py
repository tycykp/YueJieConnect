"""Verify newly inspected ColorOS phone/pad sender boundaries against original DEX.

This does not execute OEM code or probe devices. It checks the newly found local
channel, UI dispatch, and network path; original receiver/playback signatures keep
their existing verification record.
"""
import hashlib
import json
from pathlib import Path
import re
import zipfile
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/device-finder-sender"
OUT.mkdir(parents=True, exist_ok=True)
APK = ROOT / "captures/device-finder-20261108/coloros17/com.coloros.findmyphone/FindMyPhone.apk"
BASE = ROOT / "captures/device-finder-20261108/coloros17/analysis/jadx/sources"
apk_sha = hashlib.sha256(APK.read_bytes()).hexdigest()
assert apk_sha == "6e24b00e3c8689783dcd9b61287cc99bb6ab2dd688af915a7ea794666963596d"
spec = {
    "LI1/a;": {"i", "m", "o"},
    "LJ1/a;": {"o"},
    "LJ3/a;": {"b"},
    "Lcom/coloros/findmyphone/findecosystem/core/deviceinfo/FindEcosystemDeviceInfoProviderImpl;": {"isDeviceContainFindApp"},
    "Lcom/coloros/findmyphone/channel/core/strategy/FindChanStrategyManager;": {"c"},
    "Lcom/oplus/findphone/client/ViewModel/DeviceDetailViewModel;": {"f"},
}
evidence = []
bodies = {}
with zipfile.ZipFile(APK) as archive:
    for entry in ("classes.dex", "classes2.dex"):
        raw = archive.read(entry)
        for cls in DEX(raw).get_classes():
            owner = cls.get_name()
            if owner not in spec:
                continue
            methods = []
            for method in cls.get_methods():
                if method.get_name() not in spec[owner]:
                    continue
                signature = method.get_name() + method.get_descriptor().replace(" ", "")
                instructions = [ins.get_name() + " " + ins.get_output() for ins in method.get_instructions()]
                bodies[owner, method.get_name()] = "\n".join(instructions)
                methods.append(dict(signature=signature, instructions=instructions))
            assert {m["signature"].split("(")[0] for m in methods} == spec[owner], owner
            evidence.append(dict(class_name=owner, dex=entry, dex_sha256=hashlib.sha256(raw).hexdigest(), methods=methods))
assert len(evidence) == len(spec)

# The native LAN class exists but is disabled; even its direct send body only logs
# and completes a null result. Calling this empty body cannot prove remote ringing.
assert bodies["LI1/a;", "i"].splitlines() == ["const/4 v0, 0", "return v0"]
assert "LZ0/d;->O " in bodies["LI1/a;", "m"]
assert "La1/a;->a(" in bodies["LI1/a;", "m"]
local_send = bodies["LI1/a;", "o"]
assert "La1/a;->b(Ljava/lang/Object;)La1/a;" in local_send
assert "LF1/a;->b(La1/a;)V" in local_send
for line in local_send.splitlines():
    if not line.startswith("invoke-"):
        continue
    assert any(owner in line for owner in (
        "Ljava/lang/StringBuilder;", "Lcom/coloros/findmyphone/channel/api/bean/ChannelMessage;",
        "LQ3/e;", "La1/a;", "LF1/a;")), line

types = bodies["Lcom/coloros/findmyphone/findecosystem/core/deviceinfo/FindEcosystemDeviceInfoProviderImpl;", "isDeviceContainFindApp"]
for device_type in ("PHONE", "PAD", "WATCH"):
    assert "DeviceType;->" + device_type + " " in types
strategy = bodies["Lcom/coloros/findmyphone/channel/core/strategy/FindChanStrategyManager;", "c"]
assert "GetChannelRequestHandler;->getChannels(" in strategy
assert "LU6/a;->x(" in strategy and "FindChannelType;->NET " in strategy
network = bodies["LJ1/a;", "o"]
assert "LK1/a;->a(" in network and "FindNetResponse;->isSuccess()Z" in network
vm = bodies["Lcom/oplus/findphone/client/ViewModel/DeviceDetailViewModel;", "f"]
assert "SendInstructionRequest;-><init>(" in vm
assert "LH2/b;-><init>(" in vm and "LH2/d;->A " in vm
dispatch = bodies["LJ3/a;", "b"]
assert "IChannelProvider;->sendMessage(" in dispatch

paths = [BASE / path for path in (
    "I1/a.java", "J1/a.java", "J3/a.java", "K1/a.java", "U6/a.java",
    "com/coloros/findmyphone/channel/core/strategy/FindChanStrategyManager.java",
    "com/coloros/findmyphone/findecosystem/core/deviceinfo/FindEcosystemDeviceInfoProviderImpl.java",
    "com/oplus/findphone/client/ViewModel/DeviceDetailViewModel.java",
    "com/oplus/findphone/client/map/C0728t.java",
    "com/oplus/findphone/client/ViewModel/HelpOtherBrandFindConfig.java",
)]
service = (BASE / "K1/a.java").read_text(encoding="utf-8")
assert '@o("/instruction/console/v1/sendInstruction")' in service
ui = (BASE / "com/oplus/findphone/client/map/C0728t.java").read_text(encoding="utf-8")
assert "p244s3.e.RING" in ui and "p244s3.e.STOP_RING" in ui

# Bounded source inventory is an index of captured samples, not a proof that every
# installed/future OEM component lacks an entry. Exclude generated resource names.
pattern = re.compile(r"startRing|stopRing|ringFind|ring_find|btdevice_bell|响铃|查找手机", re.I)
inventory = []
captured = ROOT / "captures/device-interconnect-20261108/coloros17/analysis/jadx"
for package in ("linker", "devicehub", "mydevices", "accessory"):
    source = captured / package / "sources"
    scanned, hits = 0, []
    for path in sorted(source.rglob("*.java")):
        if path.name == "R.java" or path.name.startswith("R$"):
            continue
        scanned += 1
        matches = [{"line": n, "tokens": sorted(set(pattern.findall(line)))}
                   for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1) if pattern.search(line)]
        if matches:
            hits.append(dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest(), matches=matches))
    inventory.append(dict(package=package, java_files_scanned=scanned, hits=hits))

record = dict(
    command="python scripts/verify-device-finder-sender.py",
    apk_sha256=apk_sha,
    classes=evidence,
    source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [*paths, Path(__file__)]},
    captured_source_inventory=inventory,
    findings={
        "native_ui": "FindMyPhone DeviceDetailFragment RING/STOP_RING -> DeviceDetailViewModel.f -> send_instruction -> J3.a -> IChannelProvider",
        "local_area_net": "I1.a.i=false; eligibility errors; direct send only logs and completes null, with no transport",
        "phone_pad_fallback": "PHONE/PAD have FindApp; strategy asks server/cache and defaults NET for these device types",
        "net_sender": "J1.a.o -> K1.a /instruction/console/v1/sendInstruction; no bridge account identity was injected",
        "scope": "Captured APK/source only; reverse OEM UI to a real bridge peer remains unresolved; no runtime, account, APK install or device action",
    },
)
(OUT / "verification.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"PASS {len(evidence)} newly inspected sender/strategy DEX classes; disabled LAN and real NET path pinned")
for entry in inventory:
    print(f"{entry['package']}: {entry['java_files_scanned']} source files indexed; {len(entry['hits'])} candidate files (manual applicability review required)")
