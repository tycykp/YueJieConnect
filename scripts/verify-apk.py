"""Verify module metadata and JNI packaging; standard library only."""
import hashlib
import json
import struct
import re
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
apk = root / 'android/module/build/outputs/apk/debug/module-debug.apk'
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    assert z.read('META-INF/xposed/java_init.list').decode().strip() == 'io.github.yuejie.connect.DiscoveryModule'
    prop = dict(line.split('=', 1) for line in z.read('META-INF/xposed/module.prop').decode().splitlines() if '=' in line)
    assert prop['minApiVersion'] == prop['targetApiVersion'] == '102'
    assert prop['autoHotReload'] == 'false'
    assert set(z.read('META-INF/xposed/scope.list').decode().splitlines()) == {'com.milink.service', 'com.oplus.linker', 'com.xiaomi.mirror', 'com.coloros.findmyphone', 'com.oplus.cast', 'com.heytap.accessory'}
    libs = [n for n in z.namelist() if n.endswith('.so')]
    assert libs == ['lib/arm64-v8a/libyj_jni.so'], libs
    info = z.getinfo(libs[0])
    assert info.compress_type == zipfile.ZIP_STORED
    elf = z.read(libs[0])
    assert elf[:6] == b'\x7fELF\x02\x01', 'Not ELF64 little endian'
    assert struct.unpack_from('<H', elf, 18)[0] == 183, 'Not AArch64'
    phoff = struct.unpack_from('<Q', elf, 32)[0]
    phentsize, phnum = struct.unpack_from('<HH', elf, 54)
    aligns = []
    for i in range(phnum):
        off = phoff + i * phentsize
        if struct.unpack_from('<I', elf, off)[0] == 1:
            align = struct.unpack_from('<Q', elf, off + 48)[0]
            assert align >= 16384
            aligns.append(align)
    assert aligns
    assert b'Java_io_github_yuejie_connect_NativeCore_dispatch\0' in elf
    # ZIP local-header data offset, not central-directory extra size.
    data = apk.read_bytes()
    name_len, extra_len = struct.unpack_from('<HH', data, info.header_offset + 26)
    lib_offset = info.header_offset + 30 + name_len + extra_len
    assert lib_offset % 16384 == 0
    assert 'assets/xposed_init' not in z.namelist()
    runtime_digest = hashlib.sha256()
    runtime_files = [p for directory in ['android/module/src/main','core/src','native/src','transport/src']
                     for p in (root / directory).rglob('*') if p.is_file()]
    runtime_files += [root / name for name in ['android/module/build.gradle.kts','Cargo.lock',
                      'core/Cargo.toml','native/Cargo.toml','transport/Cargo.toml']]
    for path in sorted(runtime_files, key=lambda p: p.relative_to(root).as_posix()):
        runtime_digest.update(path.relative_to(root).as_posix().encode())
        runtime_digest.update(b'\0')
        runtime_digest.update(path.read_bytes())
        runtime_digest.update(b'\0')
    runtime_sha = runtime_digest.hexdigest()
    generated = (root / 'android/module/build/generated/source/buildConfig/debug/io/github/yuejie/connect/BuildConfig.java').read_text()
    assert re.search(r'RUNTIME_INPUT_SHA = "([a-f0-9]{64})"', generated).group(1) == runtime_sha, 'Production inputs changed after Gradle configuration'
    assert any(runtime_sha.encode() in z.read(name) for name in z.namelist() if name.endswith('.dex')), 'Runtime revision absent from APK DEX'
result = dict(apk=str(apk.relative_to(root)), bytes=apk.stat().st_size,
              sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
              lib_sha256=hashlib.sha256(elf).hexdigest(), lib_zip_offset=lib_offset,
              elf_load_alignments=aligns, api=102, abi='arm64-v8a', runtime_input_sha=runtime_sha)
(root / 'build/apk-verification.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result, indent=2))
