"""Pin the independently captured FindMyPhone runtime used by OplusFinderNativeEndpoint."""
import hashlib
import json
import re
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
root = Path(__file__).resolve().parents[1]
apk = root / "captures/device-finder-20261108/coloros17/com.coloros.findmyphone/FindMyPhone.apk"
sha = hashlib.sha256(apk.read_bytes()).hexdigest()
assert sha == "6e24b00e3c8689783dcd9b61287cc99bb6ab2dd688af915a7ea794666963596d"
required = {
    "LA3/f;": {"<init>()V", "c(Landroid/content/Context;LA3/e;)V", "b(LA3/f;)V"},
    "LA3/f$b;": set(),
    "LA3/f$c;": {"handleMessage(Landroid/os/Message;)V"},
    "LA3/f$c$a;": {"<init>(LA3/f$c;)V", "run()V"},
    "LA3/i;": {"<init>(LA3/f;Lcom/oplus/FindMyPhoneApplication;Z)V", "a(Ld1/f;)V"},
    "LA3/l;": {"a(Ld1/f;)V"},
    "LA3/k;": {"<init>(LA3/f;Landroid/content/Context;)V", "a(Ld1/f;)V"},
    "LA3/m;": {"a(Ld1/f;)V"},
    "Ld1/f;": {"b()V"},
}
fields = {
    "LA3/f;": {"a:Lcom/oplus/FindMyPhoneApplication;", "b:LA3/f$c;", "c:Landroid/media/MediaPlayer;", "e:Landroidx/appcompat/app/g;", "f:Z", "o:LA3/f$a;", "m:LA3/h;", "g:Landroid/os/Vibrator;", "i:I", "j:Ljava/lang/String;", "k:Landroid/hardware/camera2/CameraManager;"},
    "LA3/f$b;": {"a:LA3/f;"}, "LA3/l;": {"b:LA3/f;"}, "LA3/k;": {"b:LA3/f;"},
}
evidence = []
with zipfile.ZipFile(apk) as z:
    raw = z.read("classes.dex")
    for cls in DEX(raw).get_classes():
        name = cls.get_name()
        if name not in required:
            continue
        methods = {m.get_name() + m.get_descriptor().replace(" ", ""): m for m in cls.get_methods()}
        expected = required.pop(name)
        assert expected <= methods.keys(), (name, expected - methods.keys())
        actual_fields = [f.get_name() + ":" + f.get_descriptor() for f in cls.get_fields()]
        assert fields.get(name, set()) <= set(actual_fields), (name, actual_fields)
        if name == "LA3/f$c;":
            assert sum(f.endswith(":LA3/f;") for f in actual_fields) == 1
        if name == "LA3/f$c$a;":
            assert sum(f.endswith(":LA3/f$c;") for f in actual_fields) == 1
        instructions = {sig: [f"{ins.get_name()} {ins.get_output()}" for ins in methods[sig].get_instructions()] for sig in expected}
        if name in ("LA3/i;", "LA3/k;"):
            method = methods["a(Ld1/f;)V"]
            parameter = method.get_code().get_registers_size() - 1
            # Direct native playback is only valid because this stage never reads its serializer arg.
            body = list(method.get_instructions())
            uses = [i for i, ins in enumerate(body) if re.search(rf"\bv{parameter}\b", ins.get_output())]
            if uses:
                # R8 reuses the unused parameter register in A3.k: an unconditional move-result
                # overwrites it before any branch/read. This is not a read of the supplied argument.
                first = uses[0]
                assert body[first].get_name() == "move-result" and body[first].get_output() == f"v{parameter}"
                assert all(not ins.get_name().startswith(("if-", "goto", "packed-switch", "sparse-switch")) for ins in body[:first])
            if name == "LA3/i;":
                assert any("Landroid/media/MediaPlayer;->start()V" in line for line in instructions["a(Ld1/f;)V"])
        evidence.append(dict(class_name=name, methods=sorted(expected), fields=actual_fields, instructions=instructions))
assert not required, required
out = root / "build/device-finder-validation/oplus-native-runtime-verification.json"
out.write_text(json.dumps(dict(apk_sha256=sha, dex_sha256=hashlib.sha256(raw).hexdigest(), classes=evidence), indent=2), encoding="utf-8")
print(f"PASS {len(evidence)} Oplus native classes/field layouts and unused playback serializer parameter; {out}")
