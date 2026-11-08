"""Prepare installed SDK JSON sources and cached libxposed classes for notification-only JVM verification."""
import hashlib
import json
import os
import re
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
out = root / 'build/notifications-validation'
sources = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/sources/android-37.0/org/json'
destination = out / 'json-src/org/json'
destination.mkdir(parents=True, exist_ok=True)
records = []
for source in sorted(sources.glob('*.java')):
    text = source.read_text(encoding='utf-8')
    text = re.sub(r'^import (?:static )?(?:android\.|libcore\.).*;\r?\n', '', text, flags=re.M)
    text = re.sub(r'@SystemApi\(client = MODULE_LIBRARIES\)', '', text)
    text = re.sub(r'@(?:UnsupportedAppUsage|NonNull|Nullable)\b', '', text)
    (destination / source.name).write_text(text, encoding='utf-8')
    records.append(dict(path=str(source), sha256=hashlib.sha256(source.read_bytes()).hexdigest()))
assert len(records) == 6
cache = Path(os.environ['USERPROFILE']) / '.gradle/caches/modules-2/files-2.1/io.github.libxposed/api/102.0.0'
aar = next(cache.rglob('api-102.0.0.aar'))
with zipfile.ZipFile(aar) as archive:
    (out / 'xposed-api.jar').write_bytes(archive.read('classes.jar'))
records.append(dict(path=str(aar), sha256=hashlib.sha256(aar.read_bytes()).hexdigest()))
(out / 'test-dependencies.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
print('Prepared notification SDK JSON (annotations removed) and cached libxposed 102; no download.')
