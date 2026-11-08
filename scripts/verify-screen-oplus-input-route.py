"""Verify newly used PAD focus/bus/UID routing against the fixed original HeyCast DEX."""
import hashlib
import json
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
root = Path(__file__).resolve().parents[1]
apk = root / "captures/screen-input-20261108/coloros17/HeyCast.apk"
assert hashlib.sha256(apk.read_bytes()).hexdigest() == "2249716ffc2df561e8dec287d4e59bf02da4dafb19b18fb76c6981ff625cc3a0"
base = "Lcom/oplus/cast/engine/impl/odmf/display/castdisplaymanager/"
wanted = {
    base + "CastDisplayManager$a;": {
        "methods": {"onMirageDisplayTopActivityUidChanged(II)V": [], "onMirageDisplayExit(I)V": [], "onMirageDisplayCastFailed(I)V": []},
        "fields": {"a": base + "CastDisplayManager;"}},
    "Lu2/i;": {"methods": {"q()Lu2/i;": [], "s(Ljava/lang/String;)I": ["mirage_display_id"],
                            "v(Ljava/lang/String;)I": ["remote_width"], "u(Ljava/lang/String;)I": ["remote_height"]}, "fields": {}},
    "Lp2/d$a;": {"methods": {"onMirageDisplayTopActivityUidChanged(II)V": ["Lu2/i;->s", "if-ne", "Lp2/a;->onMirageDisplayTopActivityUidChanged"]}, "fields": {}},
    "Lr2/b;": {"methods": {"a(Lcom/oplus/cast/service/sdk/DeviceInfo;)Lr2/a;": ["SYNERGY_PAD", "Ls2/b;"]}, "fields": {}},
    "Ls2/b;": {"methods": {"h(Ljava/lang/String;Lorg/json/JSONObject;)V": ["Lu2/i;->s", "Lr2/c;->a"]}, "fields": {}},
}
evidence = []
with zipfile.ZipFile(apk) as archive:
    for name in archive.namelist():
        if not name.startswith("classes") or not name.endswith(".dex"):
            continue
        raw = archive.read(name)
        for cls in DEX(raw).get_classes():
            if cls.get_name() not in wanted:
                continue
            spec = wanted[cls.get_name()]
            methods = {m.get_name() + m.get_descriptor().replace(" ", ""): m for m in cls.get_methods()}
            fields = {f.get_name(): f.get_descriptor() for f in cls.get_fields()}
            for field, descriptor in spec["fields"].items():
                assert fields[field] == descriptor, (cls.get_name(), field)
            trace = {}
            for signature, tokens in spec["methods"].items():
                instructions = [i.get_name() + " " + i.get_output() for i in methods[signature].get_instructions()]
                for token in tokens:
                    assert any(token in line for line in instructions), (cls.get_name(), signature, token)
                trace[signature] = instructions
            evidence.append({"class": cls.get_name(), "dex": name, "dex_sha256": hashlib.sha256(raw).hexdigest(),
                             "fields": spec["fields"], "methods": trace})
assert len(evidence) == len(wanted)
out = root / "build/screen-repair-native-focus-validation"
out.mkdir(parents=True, exist_ok=True)
(out / "native-signatures.json").write_text(json.dumps({"apk": str(apk), "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), "evidence": evidence}, indent=2), encoding="utf-8")
print("PASS: 5 fixed original PAD/focus/bus classes; no PC/vehicle or capture-display substitution")
