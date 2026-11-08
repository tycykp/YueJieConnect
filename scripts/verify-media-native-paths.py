"""Pinned media entry audit; local original DEX only. No devices or shared build outputs.

This checks the previously unresolved VolumeValueMessage construction source across ALL four
Mirror DEX files. It cannot rule out reflection, JNI or separately loaded code, and does not claim
a native entry is supported or unsupported merely from a missing static reference.
"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import zipfile

from androguard.core.dex import DEX
from loguru import logger

logger.remove()
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/media-controls/repair1"
APK = ROOT / "captures/device-interconnect-20261108/hyperos4/com.xiaomi.mirror/MirrorOS4.apk"
EXPECTED = "9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356"
TYPE = "Lcom/xiaomi/mirror/message/VolumeValueMessage;"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    assert sha(APK) == EXPECTED, "Original Mirror APK changed"
    entries, allocations, consumers = [], [], []
    targets = {
        ("Lcom/xiaomi/mirror/l0;", "Z"),
        ("Lj2/G;", "r"),
        ("Lr3/e;", "c"),
    }
    with zipfile.ZipFile(APK) as archive:
        dex_entries = [n for n in archive.namelist() if n.endswith(".dex")]
        assert set(dex_entries) == {"classes.dex", "classes2.dex", "classes3.dex", "classes4.dex"}
        for entry in dex_entries:
            data = archive.read(entry)
            dex = DEX(data)
            count = 0
            for cls in dex.get_classes():
                for method in cls.get_methods():
                    signature = method.get_name() + method.get_descriptor().replace(" ", "")
                    selected = (cls.get_name(), method.get_name()) in targets
                    instructions = []
                    for instruction in method.get_instructions():
                        if selected:
                            instructions.append(instruction.get_name() + " " + instruction.get_output())
                        if instruction.get_name() == "new-instance" and TYPE in instruction.get_output():
                            allocations.append({"dex": entry, "class": cls.get_name(), "method": signature,
                                                "instruction": instruction.get_output()})
                    if selected:
                        targets.remove((cls.get_name(), method.get_name()))
                        consumers.append({"dex": entry, "class": cls.get_name(), "method": signature,
                                          "instructions": instructions})
                    count += 1
            entries.append({"dex": entry, "sha256": hashlib.sha256(data).hexdigest(), "methods_inspected": count})
            del dex
    assert not targets, f"Missing pinned original native methods: {targets}"
    parser = [(a["class"], a["method"]) for a in allocations]
    assert parser == [(TYPE, "parse(Ljava/nio/ByteBuffer;)Lcom/xiaomi/mirror/message/VolumeValueMessage;")], allocations
    l0 = next(c for c in consumers if c["class"] == "Lcom/xiaomi/mirror/l0;")
    assert any("AndroidPadCar" in instruction for instruction in l0["instructions"])
    car = next(c for c in consumers if c["class"] == "Lj2/G;")
    assert any("no car found" in instruction for instruction in car["instructions"])
    OUT.mkdir(parents=True, exist_ok=True)
    report = {"utc": datetime.now(timezone.utc).isoformat(), "apk": str(APK.relative_to(ROOT)),
              "apk_sha256": EXPECTED, "script_sha256": sha(Path(__file__)), "dex": entries,
              "volume_message_allocations": allocations, "original_media_consumers": consumers,
              "finding": "Only the parser statically allocates VolumeValueMessage in the four pinned Mirror DEX files. The optional send delegate has no demonstrated phone/pad producer. Car result routing is explicit in native G.r and l0.Z.",
              "limits": "Does not exclude reflection, JNI, external dynamic code, or establish device/UI behavior. No native consumer or successful media execution is fabricated."}
    target = OUT / "native-entry-audit.json"
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Inspected {len(entries)} original Mirror DEX files; {len(allocations)} VolumeValueMessage allocation, parser only.")
    print("Saved native-entry-audit.json; this is static source evidence, not device media acceptance.")


if __name__ == "__main__":
    main()
