"""Read only fixed OEM APKs; validate the native methods this workstream actually uses."""
import hashlib
import json
import zipfile
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
root = Path(__file__).resolve().parents[1]
mirror = 'Lcom/xiaomi/mirrorcontrol/'
required = {
    'hyperos4/com.xiaomi.mirror/MirrorOS4.apk': (
        '9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356', {
            mirror+'MirrorControlSink;': {
                'startMirror(Lcom/xiaomi/miplay/report/MirrorMetaInfo;Ljava/lang/String;IILandroid/view/Surface;)Z',
                'updateMirrorSurface(Landroid/view/Surface;)Z', 'stopMirror()Z', 'closeMirror()Z',
                'releaseVideoCodec()V', 'onSinkConnected()V', 'onSinkDisconnected()V', 'onSinkInfo(IJ)V'},
            mirror+'MirrorControl;': {'closeMirrorOption(J)Z'},
            mirror+'MirrorControlSource;': {'WriteStream(Z[BJ)V', 'WriteStream(Z[BJI)V'},
            'Lcom/xiaomi/mirror/sink/SinkView$d;': {'onSinkInfo(IJ)V'},
        }),
    'coloros17/com.oplus.linker/OPSynergy.apk': (
        'dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b', {
            'Lcom/oplus/linker/synergy/wisetransfer/inputservice/SynergyInputMethodService;': {
                'onStartInput(Landroid/view/inputmethod/EditorInfo;Z)V', 'onFinishInput()V', 'onDestroy()V'},
        }),
}
fields = {mirror+'MirrorControlSink;': {'mirror': mirror+'MirrorControl;', 'mirrorHandle': 'J', 'optionHandle': 'J'}}
evidence = []
for relative, (expected, wanted) in required.items():
    apk = root / 'captures/device-interconnect-20261108' / relative
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == expected, 'Unexpected OEM APK'
    found = set()
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.namelist():
            if not entry.startswith('classes') or not entry.endswith('.dex'):
                continue
            data = archive.read(entry)
            for cls in DEX(data).get_classes():
                name = cls.get_name()
                if name not in wanted:
                    continue
                methods = {m.get_name()+m.get_descriptor().replace(' ', ''): m for m in cls.get_methods()}
                assert wanted[name] <= methods.keys(), (name, wanted[name] - methods.keys())
                actual = {f.get_name(): f.get_descriptor() for f in cls.get_fields()}
                assert all(actual.get(k) == v for k, v in fields.get(name, {}).items()), name
                instructions = {}
                if name.endswith('SinkView$d;'):
                    instructions = {'onSinkInfo(IJ)V': [f'{i.get_name()} {i.get_output()}' for i in methods['onSinkInfo(IJ)V'].get_instructions()]}
                    assert any('100002' in line for line in instructions['onSinkInfo(IJ)V']), 'First-picture event changed'
                evidence.append(dict(apk=relative, apk_sha256=expected, dex=entry,
                                     dex_sha256=hashlib.sha256(data).hexdigest(), class_name=name,
                                     methods=sorted(wanted[name]), fields=fields.get(name, {}), instructions=instructions))
                found.add(name)
    assert wanted.keys() <= found, wanted.keys() - found
out = root / 'build/screen-input-validation/native-signatures.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(evidence, indent=2), encoding='utf-8')
print(f'PASS: {len(evidence)} OEM classes, {sum(len(e["methods"]) for e in evidence)} exact methods, first-picture event 100002')
