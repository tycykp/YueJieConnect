"""Check the newly hooked FusionUtils method; reuse unchanged OEM signature records."""
import hashlib
import json
from pathlib import Path
import zipfile
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
root = Path(__file__).resolve().parents[1]
out = root / 'build/file-transfer-validation'
apk = root / 'captures/device-interconnect-20261108/hyperos4/com.xiaomi.mirror/MirrorOS4.apk'
expected = '9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356'
assert hashlib.sha256(apk.read_bytes()).hexdigest() == expected
reused = []
methods = {}
for filename in ['native-routes.json', 'native-receive.json']:
    path = out / filename
    evidence = json.loads(path.read_text(encoding='utf-8-sig'))
    for record in evidence['records']:
        if not record['apk'].startswith('hyperos4/'):
            continue
        assert record['apk_sha256'] == expected
        for method in record.get('methods', []):
            methods[(record['class_name'], method)] = record
    reused.append(dict(path=path.relative_to(root).as_posix(), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
required = {
    'Lcom/xiaomi/mirror/d0;': ['onDragStart(Landroid/content/ClipData;III)V',
                              'a(Lcom/xiaomi/mirror/message/DragMessage;J)V',
                              'onDelegatePermissionReleased(Ljava/util/List;)V'],
    'Lcom/xiaomi/mirror/connection/G;': ['J()Lcom/xiaomi/mirror/connection/G;',
                                       'J0(I)Ljava/lang/String;',
                                       't0(Ljava/lang/String;)Lcom/xiaomi/mirror/l0;'],
}
for cls, wanted in required.items():
    for method in wanted:
        assert (cls, method) in methods, (cls, method)
start = methods[('Lcom/xiaomi/mirror/d0;', required['Lcom/xiaomi/mirror/d0;'][0])]
instructions = start['instructions'][required['Lcom/xiaomi/mirror/d0;'][0]]
conversion = next(i for i, text in enumerate(instructions) if 'LV3/V;->x0' in text)
dispatch = next(i for i, text in enumerate(instructions) if 'Lq4/A;->P' in text)
assert conversion < dispatch
record = None
signature = 'P(Lcom/xiaomi/mirror/message/Message;JLjava/lang/String;)V'
with zipfile.ZipFile(apk) as archive:
    for entry in archive.namelist():
        if not entry.startswith('classes') or not entry.endswith('.dex'):
            continue
        data = archive.read(entry)
        for cls in DEX(data).get_classes():
            if cls.get_name() != 'Lq4/A;':
                continue
            found = {m.get_name() + m.get_descriptor().replace(' ', ''): m for m in cls.get_methods()}
            assert signature in found
            selected = found[signature]
            assert selected.get_access_flags() & 0x8  # static
            instructions = [f'{i.get_name()} {i.get_output()}' for i in selected.get_instructions()]
            assert any('Lcom/xiaomi/mirror/connection/G;->t0' in i for i in instructions)
            assert any('Lcom/xiaomi/mirror/message/MessageManagerImpl;->send' in i for i in instructions)
            record = dict(apk_sha256=expected, dex=entry, dex_sha256=hashlib.sha256(data).hexdigest(),
                          class_name=cls.get_name(), method=signature, instructions=instructions)
            break
        if record:
            break
assert record
(out / 'native-xiaomi-send.json').write_text(json.dumps(dict(
    scope='One new source hook signature and unchanged source/peer signatures reused; no runtime claim',
    script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), reused=reused, record=record),
    ensure_ascii=False, indent=2), encoding='utf-8')
print('PASS: new q4.A.P signature and original conversion-before-dispatch; reused unchanged source/peer evidence')
