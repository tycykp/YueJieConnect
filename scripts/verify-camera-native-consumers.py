"""Verify only newly used native still/HAL/hardware contracts against the captured original APK."""
from pathlib import Path
import hashlib
import json
import zipfile
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "captures/device-interconnect-20261108"
APK = BASE / "hyperos4/com.milink.service/base.apk"
OUT = ROOT / "build/camera-validation/native-consumers-dex.json"

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

required = {
    "classes3.dex": {
        "Lcom/xiaomi/dist/hardware/data/HardwareInfo;": {
            "getDeviceId()Ljava/lang/String;", "getDeviceType()I", "getDhId()Ljava/lang/String;",
            "getDhType()Lcom/xiaomi/dist/hardware/data/DHType;", "getDhAttr()Ljava/lang/String;",
        },
        "Lcom/xiaomi/dist/camera/kit/VirtualCamera;": {
            "getDeviceId()Ljava/lang/String;", "getDeviceType()I", "getDhId()Ljava/lang/String;",
            "getCameraId()I", "getFacing()Ljava/lang/String;",
        },
        "Lcom/xiaomi/dist/camera/kit/IRemoteCameraViewController;": {
            "takePicture(Ljava/util/List;)V",
        },
    },
    "classes4.dex": {
        "Lcom/xiaomi/vtcamera/injection/VirtualCamera2;": {
            "startCapture([Landroid/view/Surface;)V", "getRequestedOutputStreams()Ljava/util/List;",
            "configureOutputStreams([Lvendor/xiaomi/hardware/camera/synthetic/OutputConfiguration;)V",
        },
        "Lcom/xiaomi/vtcamera/injection/VirtualCamera2$StreamConfiguration;": {
            "getId()J", "surface()Landroid/view/Surface;", "width()I", "height()I", "format()I", "usage()I",
        },
    },
}
surface_signatures = {
    "getSurfaceId(Landroid/view/Surface;)J", "nativeGetJpegFooterSize()I",
    "setSurfaceDimens(Landroid/view/Surface;II)V", "produceFrame(Landroid/view/Surface;[BIII)V",
}
evidence = []
assert sha(APK) == "4d4ec36791dcc58b12efde494d24d0a5c8c219213433c1f986996c5277f95bd3"
with zipfile.ZipFile(APK) as archive:
    for dex_name, specs in required.items():
        raw = archive.read(dex_name)
        dex = DEX(raw)
        by_name = {c.get_name(): c for c in dex.get_classes()}
        classes = []
        for name, methods in specs.items():
            cls = by_name[name]
            actual = {m.get_name() + m.get_descriptor().replace(" ", "") for m in cls.get_methods()}
            assert methods <= actual, (name, methods - actual)
            fields = {f.get_name(): f.get_descriptor() for f in cls.get_fields()}
            if name == "Lcom/xiaomi/vtcamera/injection/VirtualCamera2;":
                assert fields["mClientUid"] == "I"
                assert fields["mPackageName"] == "Ljava/lang/String;"
                assert fields["mVirtualCameraCallback"] == "Lvendor/xiaomi/hardware/camera/synthetic/IVirtualCameraCallback;"
            classes.append(dict(name=name, methods=sorted(methods), fields=fields))
        entry = dict(dex=dex_name, sha256=hashlib.sha256(raw).hexdigest(), classes=classes)
        if dex_name == "classes4.dex":
            refs = {m.get_name() + m.get_descriptor().replace(" ", "") for m in dex.get_methods()
                    if m.get_class_name() == "Lcom/xiaomi/camera/companion/util/SurfaceUtils;"}
            assert surface_signatures <= refs, surface_signatures - refs
            entry["external_surface_utils_method_refs"] = sorted(surface_signatures)
            entry["external_dependency_boundary"] = "Method references verified; SurfaceUtils implementation is a ROM dependency, not supplied/replaced."
        evidence.append(entry)

# Retain exact native source excerpts backing the new usecase/format/footer mapping.
milink = BASE / "hyperos4/analysis/jadx/com.milink.service/sources"
paths = [
    milink / "com/xiaomi/vtcamera/injection/VirtualCamera2.java",
    milink / "com/xiaomi/vtcamera/injection/VirtualCamera2Controller.java",
    milink / "com/xiaomi/dist/camera/kit/VirtualCameraClient.java",
]
texts = [p.read_text(encoding="utf-8") for p in paths]
assert "streamConfiguration.usage() == 5" in texts[1] and "streamConfiguration.format() == 256" in texts[1]
assert "SurfaceUtils.produceFrame(this.mSurface, bArr, length, 1, 33)" in texts[1]
assert 'getJSONArray("attr")' in texts[2] and 'getInt("cameraId")' in texts[2]
sources = [dict(path=str(p.relative_to(ROOT)), sha256=sha(p)) for p in paths]

oplus_paths = [
    "com/oplus/linker/feature/camera/client/domain/CameraSource.java",
    "com/oplus/linker/feature/camera/client/provider/fuyao/FuyaoCameraProvider.java",
    "com/oplus/linker/feature/camera/client/connection/CameraDfmServiceId.java",
    "com/oplus/synergy/scene/ability/control/action/local/CameraAction.java",
]
oplus_root = BASE / "coloros17/analysis/jadx/linker/sources"
oplus_sources = [dict(path=str((oplus_root / p).relative_to(ROOT)), sha256=sha(oplus_root / p)) for p in oplus_paths]
assert "CameraDfmServiceId.FUYAO_LUMO" in (oplus_root / oplus_paths[1]).read_text(encoding="utf-8")
assert "EmptyList" in (oplus_root / oplus_paths[3]).read_text(encoding="utf-8")
oplus_apk = BASE / "coloros17/com.oplus.linker/OPSynergy.apk"
assert sha(oplus_apk) == "dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b"
with zipfile.ZipFile(oplus_apk) as archive:
    catalog_raw = archive.read("classes5.dex")
    catalog_dex = DEX(catalog_raw)
    catalog_name = "Lcom/oplus/interconnect/base/api/BuiltinServiceCatalog;"
    catalog = next(c for c in catalog_dex.get_classes() if c.get_name() == catalog_name)
    methods = {m.get_name() + m.get_descriptor().replace(" ", "") for m in catalog.get_methods()}
    assert "getSERVICES()Ljava/util/List;" in methods
    strings = set(catalog_dex.get_strings())
    vdc = "com.oplus.vdc/com.oplus.vdm.service.CameraOdmfInterconnectHostService"
    assert {vdc, "camera.odmf", "[camera_odmf@1.0]"} <= strings
    vdc_finding = dict(apk_sha256=sha(oplus_apk), dex="classes5.dex",
                       dex_sha256=hashlib.sha256(catalog_raw).hexdigest(),
                       class_name=catalog_name, service="camera.odmf", component=vdc,
                       capabilities="[camera_odmf@1.0]", sample_available=False,
                       limit="Service catalog proves the host identity, not phone-pad applicability or camera consumption.")
catalog_source = oplus_root / "com/oplus/interconnect/base/api/BuiltinServiceCatalog.java"
oplus_sources.append(dict(path=str(catalog_source.relative_to(ROOT)), sha256=sha(catalog_source)))
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(dict(
    result="PASS", command="python scripts/verify-camera-native-consumers.py", apk_sha256=sha(APK),
    evidence=evidence, native_source_mapping=sources, oplus_reviewed_candidates=oplus_sources,
    oplus_vdc_candidate=vdc_finding,
    oplus_finding="Reviewed local Linker/DeviceHub/MyDevices/HeyCast camera candidates still do not establish an ordinary phone-pad native consumer. FUYAO_LUMO, third-party DFS and empty Scene CameraAction are not such proof. No unsupported OplusCamera stub added.",
    script_sha256=sha(Path(__file__)), device_tested=False,
    scope="Original native contracts and source mapping only; no hook installation, capture, Surface consumption or APK validation."
), indent=2), encoding="utf-8")
print(f"PASS new camera native contracts: {OUT}")
