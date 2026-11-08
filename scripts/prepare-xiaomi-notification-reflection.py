"""Prepare only the captured plain-Java DTO for new host reflection tests; no OEM UI emulation."""
import hashlib
import json
from pathlib import Path
import re

root=Path(__file__).resolve().parents[1]
source=root/'captures/device-interconnect-20261108/hyperos4/analysis/jadx/com.milink.service/sources/com/xiaomi/dist/notification/common/data/NotificationMessage.java'
out=root/'build/notifications-validation/repair-round3/distributed/reflection-src'
target=out/'com/xiaomi/dist/notification/common/data/NotificationMessage.java'
target.parent.mkdir(parents=True,exist_ok=True)
raw=source.read_text(encoding='utf-8')
text=re.sub(r'^import (androidx\.annotation\.(NonNull|Nullable)|com\.google\.gson\.annotations\.Expose);\s*$', '',raw,flags=re.MULTILINE)
text=re.sub(r'@(NonNull|Nullable|Expose)\s*','',text)
target.write_text(text,encoding='utf-8')
marker=out/'dj/a.java';marker.parent.mkdir(parents=True,exist_ok=True)
marker.write_text('package dj; public final class a {}\n',encoding='utf-8')
(out/'provenance.json').write_text(json.dumps({'source':str(source.relative_to(root)),'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
    'prepared_sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'transform':'Only NonNull/Nullable/Expose imports and annotations removed; original Builder/getter/constructor logic retained. dj.a is an unused synthetic constructor marker type. No Android UI/native result stub.'},indent=2),encoding='utf-8')
