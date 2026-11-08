"""Verify the camera-only native signatures against original APK DEX, without touching devices."""
from pathlib import Path
import hashlib
import json
import zipfile
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/camera-validation/native-samples.json"
BASE = ROOT / "captures/device-interconnect-20261108"
KIT = "Lcom/xiaomi/dist/camera/kit/"
OPLUS = "Lcom/oplus/linker/feature/camera/client/"
specs = [
    ("hyperos4/com.milink.service/base.apk",
     "4d4ec36791dcc58b12efde494d24d0a5c8c219213433c1f986996c5277f95bd3", {
        "classes2.dex": {
            "Lcom/miui/circulate/api/protocol/synergy/manager/CameraSynergyManager;": {
                "connectCameraSynergy(Ljava/lang/String;Ljava/lang/String;)V",
                "closeCameraSynergy(Ljava/lang/String;Ljava/lang/String;)V",
            },
        },
        "classes3.dex": {
            KIT + "VirtualCamera$OpenCameraOptions;": {
                "getCaptureSurface()Landroid/view/Surface;", "getPreviewSurface()Landroid/view/Surface;",
                "getPreviewSize()" + KIT + "VirtualCamera$ParamSize;",
            },
            KIT + "VirtualCamera$ParamSize;": {"getWidth()I", "getHeight()I"},
            KIT + "VirtualCameraClient;": {
                "openCamera(" + KIT + "VirtualCamera;" + KIT + "VirtualCamera$OpenCameraOptions;)V",
                "closeCamera()V", "deInit()V",
            },
            KIT + "IRemoteCameraViewController;": {
                "openCamera(Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)V",
                "closeCamera(Ljava/lang/String;Ljava/lang/String;)V",
            },
        },
    }),
    ("coloros17/com.oplus.linker/OPSynergy.apk",
     "dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b", {
        "classes6.dex": {
            OPLUS + "domain/CameraSource;": {"values()[" + OPLUS + "domain/CameraSource;"},
            OPLUS + "domain/CameraProvider;": {"connect(Ljava/lang/String;)V", "disconnect(Ljava/lang/String;)V"},
            OPLUS + "connection/CameraDfmServiceId;": set(),
        },
    }),
]
evidence = []
for relative, expected, dex_specs in specs:
    apk = BASE / relative
    apk_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
    assert apk_hash == expected, (relative, "APK changed")
    with zipfile.ZipFile(apk) as archive:
        for dex_name, classes in dex_specs.items():
            raw = archive.read(dex_name)
            remaining = dict(classes)
            found = []
            for cls in DEX(raw).get_classes():
                name = cls.get_name()
                if name not in remaining:
                    continue
                actual = {m.get_name() + m.get_descriptor().replace(" ", "") for m in cls.get_methods()}
                required = remaining.pop(name)
                assert required <= actual, (name, required - actual)
                fields = sorted(f.get_name() for f in cls.get_fields())
                if name == OPLUS + "domain/CameraSource;":
                    assert "FUYAO" in fields and "THIRD_PARTY" in fields
                    assert "PHONE" not in fields and "PAD" not in fields
                found.append(dict(name=name, signatures=sorted(required), fields=fields))
            assert not remaining, remaining
            evidence.append(dict(apk=relative, apk_sha256=apk_hash, dex=dex_name,
                                 dex_sha256=hashlib.sha256(raw).hexdigest(), classes=found))
        if relative.startswith("coloros17"):
            asset = archive.read("assets/default_camera_supported_device_config.xml")
            assert b"Insta360 GO Ultra" in asset and b'source="third_party"' in asset
            evidence.append(dict(apk=relative, asset="assets/default_camera_supported_device_config.xml",
                                 sha256=hashlib.sha256(asset).hexdigest(),
                                 finding="External camera client configuration; not evidence of phone-pad camera sharing"))
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(dict(result="PASS", evidence=evidence,
                              script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                              scope="Original sample signatures only; no native hook install or runtime claim"),
                          indent=2), encoding="utf-8")
print(f"PASS camera native signatures: {OUT}")
