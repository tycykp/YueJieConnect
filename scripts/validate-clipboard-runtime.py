"""Only validate the new runtime-evidence gate; no ADB, clipboard or prior business tests."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'scripts/validate-clipboard-native.py'
spec = importlib.util.spec_from_file_location('clipboard_native', SOURCE)
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


def main():
    revision, package = 'a' * 64, 'com.oplus.linker'
    base = dict(status='ok', expected_revision=revision, clients=[dict(uid=10234, pid=4321,
        package=package, revision=revision, matches=True, age_ms=345)])
    assert native.runtime_matches(base, revision, package)
    invalid = [None, {}, dict(base, status='error'), dict(base, expected_revision='b' * 64),
               dict(base, clients=[]), dict(base, clients=None), dict(base, clients={}), dict(base, clients=[None]),
               dict(base, clients=base['clients'] * 65)]
    for key, value in [('matches', False), ('matches', 'true'), ('revision', 'b' * 64), ('uid', 0),
                       ('pid', 0), ('age_ms', 10_000), ('age_ms', -1), ('age_ms', True), ('pid', '4321'),
                       ('package', 'com.android.shell')]:
        item = copy.deepcopy(base)
        item['clients'][0][key] = value
        invalid.append(item)
    bad_second = copy.deepcopy(base)
    bad_second['clients'].append(dict(base['clients'][0], revision='b' * 64, package='com.xiaomi.mirror'))
    invalid.append(bad_second)
    for value in invalid:
        assert not native.runtime_matches(value, revision, package)
    assert not native.runtime_matches(base, None, package)
    assert not native.runtime_matches(base, 'a' * 63, package)
    files = [SOURCE, Path(__file__)]
    out = ROOT / 'build/clipboard-validation/runtime-gate-host'
    out.mkdir(parents=True, exist_ok=True)
    count = 1 + len(invalid) + 2
    (out / 'evidence.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-runtime.py',
        result='passed', assertions=count, apk=None, device_operations=False,
        source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}), indent=2), encoding='utf-8')
    print(f'PASS {count} runtime gate assertions; no device operation')


if __name__ == '__main__':
    main()
