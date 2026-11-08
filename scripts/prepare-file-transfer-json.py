"""Compile tests against the installed Android JSON source, removing annotations only."""
import hashlib
import json
import os
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/sources/android-37.0/org/json'
out = root / 'build/file-transfer-validation/json-src/org/json'
out.mkdir(parents=True, exist_ok=True)
ledger = []
for source in sorted(sdk.glob('*.java')):
    text = source.read_text(encoding='utf-8')
    text = re.sub(r'^import (?:static )?(?:android\.|libcore\.).*;\r?\n', '', text, flags=re.M)
    text = re.sub(r'@SystemApi\(client = MODULE_LIBRARIES\)', '', text)
    text = re.sub(r'@(?:UnsupportedAppUsage|NonNull|Nullable)\b', '', text)
    (out / source.name).write_text(text, encoding='utf-8')
    ledger.append(dict(path=str(source), sha256=hashlib.sha256(source.read_bytes()).hexdigest()))
assert len(ledger) == 6, 'Expected six installed Android JSON sources'
(out.parents[2] / 'json-sources.json').write_text(json.dumps(ledger, indent=2), encoding='utf-8')
