"""Inventory sampled Mirror message models and explicit numeric class types, not guessed capabilities."""
import hashlib
import json
import re
from pathlib import Path
root=Path(__file__).resolve().parents[1]
base=root/'captures/device-interconnect-20261108/hyperos4/analysis/jadx/com.xiaomi.mirror/sources/com/xiaomi/mirror/message'
models=[]
for path in sorted(base.glob('*.java')):
 text=path.read_text(encoding='utf-8')
 match=re.search(r'public int getClassType\(\)\s*\{\s*return (\d+);',text)
 if not match:continue
 models.append({'class':path.stem,'class_type':int(match.group(1)),
  'source':path.relative_to(root).as_posix(),'source_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
  'has_parse':bool(re.search(r'\bparse\(',text)),
  'adapted':False,'evidence':'JADX explicit method; wire format/runtime availability not implied'})
out={'sample':'com.xiaomi.mirror 18.02.01-26.09.10','scope':'All explicit getClassType models in sampled message package; not exhaustive native-library or other-package protocols',
 'message_models':models}
(root/'adapters/hyperos4/mirror-message-inventory.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
print('Inventoried',len(models),'explicit Mirror message type models; none advertised as implemented.')
