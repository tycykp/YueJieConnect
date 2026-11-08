"""Inspect only the newly captured synthetic camera AIDL library, without calling it."""
import hashlib
import json
from pathlib import Path
import zipfile
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "captures/camera-native-20261108/hyperos4/companion-20261109-001516-719/companion.jar"
OUT = ROOT / "build/camera-validation/companion-sample-audit.json"
raw_jar = JAR.read_bytes()
assert hashlib.sha256(raw_jar).hexdigest() == "6c53ef7b9581250be8b588644e20014b779525003e3af1545e9124f7efa93088"
with zipfile.ZipFile(JAR) as archive:
    raw = archive.read("classes.dex")
dex = DEX(raw)
classes = {cls.get_name(): cls for cls in dex.get_classes()}
assert len(classes) == 34
assert "Lcom/xiaomi/camera/companion/util/SurfaceUtils;" not in classes
camera = classes["Lvendor/xiaomi/hardware/camera/synthetic/IVirtualCamera;"]
methods = {m.get_name() + m.get_descriptor().replace(" ", "") for m in camera.get_methods()}
assert {"close()V", "configureOutputStreams([Lvendor/xiaomi/hardware/camera/synthetic/OutputConfiguration;)V",
        "startCapture([Landroid/view/Surface;)V"} <= methods
event = classes["Lvendor/xiaomi/hardware/camera/synthetic/EventType;"]
events = {f.get_name(): f.get_init_value().get_value() for f in event.get_fields()}
assert events == dict(DEVICE_ORIENTATION_CHANGED=7, FRAME_DROPPED=2, LAYOUT_CHANGED=6,
                     PARAMETER_CHANGED=5, SERVER_DIED=8, STREAM_ERROR=4, STREAM_STARTED=0,
                     STREAM_STOPPED=1, TIMEOUT=3, VENDOR_EXTENDED=1000)
result = dict(result="PASS", command="python scripts/verify-camera-companion.py",
              script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              library=str(JAR.relative_to(ROOT)), library_sha256=hashlib.sha256(raw_jar).hexdigest(),
              dex_sha256=hashlib.sha256(raw).hexdigest(), class_count=len(classes),
              virtual_camera_methods=sorted(methods), event_constants=events,
              surface_utils_implementation_present=False,
              limit="This file provides AIDL/registrar definitions, not SurfaceUtils/JNI or a native JPEG-acquired result. Vendor-extended events are not assumed to be acquisition receipts. No conclusion about uninspected files or native timestamp behavior.",
              scope="New original file static inspection only; no API/camera/native consumer/APK/device execution.")
OUT.write_text(json.dumps(result, indent=2), encoding="utf-8")
print(f"PASS new camera companion AIDL audit: {OUT}")
