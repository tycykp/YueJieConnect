"""Record the new VDC/ODMF original samples; absence claims are limited to these two files."""
import hashlib
import json
from pathlib import Path
import zipfile

from androguard.core.apk import APK
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "captures/camera-native-20261108/coloros17"
VDC = BASE / "vdc-20261109-000245-994/split-00.apk"
ODMF = BASE / "odmf-20261109-001142-934/odmf.jar"
OUT = ROOT / "build/camera-validation/vdc-odmf-sample-audit.json"
HOST = "com.oplus.vdm.service.CameraOdmfInterconnectHostService"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


assert sha(VDC) == "c5faa71e3724846b98ecae9da8b6828059df51e41a231d0141082c1f626b682d"
assert sha(ODMF) == "e2764db867e1038122d129015921bcb332de28a8dd5f8971f2cf26102dabf0c5"
apk = APK(str(VDC))
assert apk.get_package() == "com.oplus.vdc"
assert apk.get_androidversion_code() == "17000006"
assert HOST not in apk.get_services()
assert "android.permission.CAMERA" not in apk.get_permissions()
samples = []
for path in [VDC, ODMF]:
    dexes = []
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if not name.endswith(".dex"):
                continue
            raw = archive.read(name)
            dex = DEX(raw)
            classes = {cls.get_name(): cls for cls in dex.get_classes()}
            assert "L" + HOST.replace(".", "/") + ";" not in classes
            cameras = [cls for cls in classes if "camera" in cls.lower()]
            non_ortc_cameras = [cls for cls in cameras if not cls.startswith("Lcom/oplus/ortc/")]
            if path == VDC:
                # The SDK enum advertises a category; it is not the missing camera implementation.
                enum = classes.get("Lcom/oplus/ocs/vdm/enums/VirtualDeviceType;")
                if enum is not None:
                    assert "DEVICE_TYPE_CAMERA" in {f.get_name() for f in enum.get_fields()}
            dexes.append(dict(name=name, sha256=hashlib.sha256(raw).hexdigest(),
                              class_count=len(classes), requested_camera_host_present=False,
                              camera_classes=cameras, non_ortc_camera_classes=non_ortc_cameras))
    samples.append(dict(path=str(path.relative_to(ROOT)), sha256=sha(path), dexes=dexes))

result = dict(
    result="PASS", command="python scripts/verify-camera-vdc.py", script_sha256=sha(Path(__file__)),
    package=apk.get_package(), version_code=apk.get_androidversion_code(),
    version_name=apk.get_androidversion_name(), services=apk.get_services(),
    camera_permission=False, libraries=apk.get_libraries(), expected_host=HOST,
    samples=samples,
    finding="The Linker service catalog host is absent from this installed VDCService APK manifest and from all DEX definitions in this APK and its captured ODMF library. ORTC Camera1/Camera2 classes and SDK DEVICE_TYPE_CAMERA are not an actual phone-pad native consumer route.",
    limit="No claim that the entire ROM or another build lacks camera sharing. A real installed phone-pad consumer/Surface route and authorization callback remain unproven. No unsupported stub or device-type substitution added.",
    collection_ledgers=[str((BASE / p).relative_to(ROOT)) for p in [
        "vdc-20261109-000245-994/result.json", "odmf-20261109-001142-934/result.json"]],
    scope="New original file inspection only; no API invocation, camera exposure, image, native consumer execution, APK build/install or pairing test.")
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
print(f"PASS new VDC/ODMF original sample audit: {OUT}")
