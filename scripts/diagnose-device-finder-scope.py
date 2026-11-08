"""Read only this module's LSPosed scope; transient raw SQLite snapshot is deleted."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/device-finder-repair/scope-diagnosis.json'
LOCK = ROOT / 'build/coordination-locks/devices.lock'
lease = dict(task='device-finder-scope-diagnosis', threadId='01a1bbe4-f070-7731-9b98-3abbf216410c',
             utc=datetime.datetime.now(datetime.timezone.utc).isoformat(), lease=uuid.uuid4().hex)
record = dict(command='python scripts/diagnose-device-finder-scope.py', utc=lease['utc'],
              script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              scope='Read-only LSPosed configuration for io.github.qiangjian.connect only; no UI or restart')
owned = False
try:
    fd = os.open(LOCK, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    owned = True
    with os.fdopen(fd, 'w', encoding='utf-8') as handle:
        json.dump(lease, handle)
    with tempfile.TemporaryDirectory(prefix='qj-finder-scope-') as temporary:
        db = Path(temporary) / 'modules_config.db'
        for suffix in ('', '-wal', '-shm'):
            result = subprocess.run(['C:/adb/adb.exe', '-s', '3B161Q00YN200000', 'exec-out',
                                     'su', '-c', 'cat /data/adb/lspd/config/modules_config.db' + suffix],
                                    capture_output=True, timeout=20)
            if result.returncode:
                raise RuntimeError('scope_snapshot_read_failed')
            Path(str(db) + suffix).write_bytes(result.stdout)
        connection = sqlite3.connect(db)
        try:
            record['schema'] = connection.execute("SELECT name,sql FROM sqlite_master WHERE type='table'").fetchall()
            columns = [r[1] for r in connection.execute('PRAGMA table_info(modules)')]
            rows = connection.execute('SELECT * FROM modules WHERE module_pkg_name=?', ('io.github.qiangjian.connect',)).fetchall()
            record['module'] = [dict(zip(columns, row)) for row in rows]
            scope_columns = [r[1] for r in connection.execute('PRAGMA table_info(scope)')]
            record['module_scope'] = [dict(zip(scope_columns, row)) for row in connection.execute(
                'SELECT * FROM scope WHERE module_pkg_name=?', ('io.github.qiangjian.connect',))]
            state_columns = [r[1] for r in connection.execute('PRAGMA table_info(modules_state)')]
            record['module_state'] = [dict(zip(state_columns, row)) for row in connection.execute(
                'SELECT * FROM modules_state WHERE module_pkg_name=?', ('io.github.qiangjian.connect',))]
        finally:
            connection.close()
    record['result'] = 'collected'
except FileExistsError:
    record['result'] = 'lock-busy-no-device-commands'
except Exception as failure:
    record['result'] = 'incomplete'
    record['error'] = str(failure)
finally:
    if owned and json.loads(LOCK.read_text(encoding='utf-8')).get('lease') == lease['lease']:
        LOCK.unlink()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(record, ensure_ascii=False, indent=2))
