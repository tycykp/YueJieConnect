"""Verify the newly used native Mirror listener and ownership field only."""
import hashlib
import json
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
ROOT = Path(__file__).resolve().parents[1]
apk = ROOT / 'captures/device-interconnect-20261108/hyperos4/com.xiaomi.mirror/MirrorOS4.apk'
sha = hashlib.sha256(apk.read_bytes()).hexdigest()
assert sha == '9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356'
expected = {'LA3/b;': {'b()LA3/b;', 'a(Ljava/lang/String;)V', 'd()V'},
            'LA3/b$c;': {'<init>()V', 'onPrimaryClipChanged()V'}}
evidence = []
with zipfile.ZipFile(apk) as archive:
    for entry in sorted(n for n in archive.namelist() if n.endswith('.dex')):
        content = archive.read(entry)
        for cls in DEX(content).get_classes():
            name = cls.get_name()
            if name not in expected:
                continue
            methods = {m.get_name() + m.get_descriptor().replace(' ', ''): m for m in cls.get_methods()}
            assert expected[name] <= methods.keys(), name
            fields = [f.get_name() + ':' + f.get_descriptor() for f in cls.get_fields()]
            if name == 'LA3/b;':
                assert 'a:LA3/b$c;' in fields
            instructions = {key: [i.get_name() + ' ' + i.get_output() for i in methods[key].get_instructions()]
                            for key in expected[name]}
            if name == 'LA3/b$c;':
                body = '\n'.join(instructions['onPrimaryClipChanged()V'])
                assert 'LV3/V;->G()LV3/V;' in body and 'LV3/V;->S()V' in body
                assert 'Landroid/content/ClipboardManager$OnPrimaryClipChangedListener;' in cls.get_interfaces()
            evidence.append(dict(name=name, dex=entry, dex_sha256=hashlib.sha256(content).hexdigest(), fields=fields, instructions=instructions))
assert len(evidence) == 2
out = ROOT / 'build/clipboard-validation/mirror-source-lease-dex.json'
out.write_text(json.dumps(dict(apk_sha256=sha, classes=evidence, scope='Only added listener/lifecycle descriptors; does not prove device behavior'), indent=2), encoding='utf-8')
print('PASS: two native listener classes, five methods, actual listener ownership field and V.S event delegation')
