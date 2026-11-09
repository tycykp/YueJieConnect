"""Check the reflection boundary against the captured, pinned ColorOS APK (no device writes)."""
from pathlib import Path
from zipfile import ZipFile
from loguru import logger
from androguard.core.dex import DEX

logger.disable("androguard")
root = Path(__file__).resolve().parents[1]
apk = root / "captures/device-interconnect-20261108/coloros17/com.oplus.linker/OPSynergy.apk"
base = "Lcom/oplus/interconnect/"
required = {
    base + "nfc/NfcEventRepository;": {"getRegisterNfcData", "getNfcAnimationView", "share"},
    base + "nfc/ui/animation/NfcTouchingAnimationView;": {
        "startAnimation", "isRunning", "startCardEnterAnimation-BWLJW6A", "cancelCard",
        "allAnimationCancel", "registerCardOperateCallback", "unRgisterCardOperateCallback", "cancelWaitUserTouchTimeout"},
    base + "nfc/resource/ShareResourceManagerImpl;": {"buildCardViewData-gIAlu-s"},
    base + "nfc/resource/ShareResourceManagerImpl$Companion;": {"getShareResourceManager"},
    base + "nfc/share/operation/ShareOperationEntity;": {"getAgent", "getData", "getPrebuiltCardViewData", "getSealedResultDeffer"},
    base + "share/sdk/data/SharedData;": {"getRecords"},
    base + "share/sdk/data/SharedRecord;": {"getUri"},
    base + "nfc/ShareAgent;": {"getAgentId", "getPkg", "getPid", "getAppName", "getCallback"},
    base + "nfc/ui/cardmodleview/CircularProgressBar;": {"setProgress"},
    "Lkotlin/Result;": {"<init>"},
    "Lv10;": {"N"},
}
fields = {
    base + "nfc/ui/animation/NfcTouchingAnimationView;": {"progressBar", "card_progress_view", "loadingview"},
    "Lkotlin/Result;": {"value"},
}
classes = {}
with ZipFile(apk) as archive:
    for name in archive.namelist():
        if not (name.startswith("classes") and name.endswith(".dex")):
            continue
        for cls in DEX(archive.read(name)).get_classes():
            class_name = cls.get_name()
            classes[class_name] = (cls.get_superclassname(), {method.get_name() for method in cls.get_methods()},
                                   {field.get_name() for field in cls.get_fields()})
errors = []
for class_name, methods in required.items():
    if class_name not in classes:
        errors.append((class_name, "missing class"))
        continue
    inherited = set()
    current = class_name
    while current in classes:
        parent, names, _ = classes[current]
        inherited.update(names)
        current = parent
    missing = methods - inherited
    if missing:
        errors.append((class_name, "methods", missing))
    missing = fields.get(class_name, set()) - classes[class_name][2]
    if missing:
        errors.append((class_name, "fields", missing))
assert not errors, errors
print(f"PASS: {len(required)} pinned native classes, method names and renderer fields")
