"""Bounded original DEX inventory for native local-only finder presentation."""
from pathlib import Path
import hashlib,json,zipfile
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
root=Path(__file__).resolve().parents[1]
apk=root/'captures/device-finder-20261108/coloros17/com.coloros.findmyphone/FindMyPhone.apk'
assert hashlib.sha256(apk.read_bytes()).hexdigest()=='6e24b00e3c8689783dcd9b61287cc99bb6ab2dd688af915a7ea794666963596d'
names={
 'Lcom/oplus/findphone/client/map/D;', 'Lcom/oplus/findphone/client/map/B;',
 'Lcom/oplus/findphone/client/map/t;', 'Lcom/oplus/findphone/client/map/z;',
 'Lhc/i$b;', 'Lhc/i$e;', 'Lhc/i;',
 'Lwc/e;', 'Lwc/e$a;', 'Lwc/e$c;',
 'Landroidx/recyclerview/widget/z;',
 'Lcom/oplus/findphone/client/ViewModel/DeviceDetailViewModel;',
 'Lcom/oplus/findphone/client/device/DeviceListItem;',
 'Lcom/oplus/findphone/client/device/devicedetail/DeviceDetailData;',
 'Lcom/oplus/findphone/client/device/devicedetail/SupportInfo;',
 'Lcom/oplus/findphone/client/device/devicedetail/a;',
 'Lwc/l;', 'Lcom/oplus/findphone/client/map/e;',
 'Lcom/oplus/findphone/client/map/N;',
}
evidence=[]
with zipfile.ZipFile(apk) as archive:
 for entry in ('classes.dex','classes2.dex'):
  raw=archive.read(entry)
  for cls in DEX(raw).get_classes():
   if cls.get_name() not in names:continue
   names.remove(cls.get_name())
   evidence.append(dict(name=cls.get_name(),dex=entry,dex_sha256=hashlib.sha256(raw).hexdigest(),
     methods=[m.get_name()+m.get_descriptor().replace(' ','') for m in cls.get_methods()],
     fields=[f.get_name()+':'+f.get_descriptor() for f in cls.get_fields()]))
out=root/'build/device-finder-repair/ui-original-dex.json'
out.write_text(json.dumps(dict(classes=evidence,missing=sorted(names)),indent=2),encoding='utf-8')
for cls in evidence:
 print(cls['name'],cls['methods'] if cls['name'] not in (
  'Lcom/oplus/findphone/client/device/devicedetail/DeviceDetailData;',
  'Lcom/oplus/findphone/client/device/devicedetail/SupportInfo;') else '<getters/setters recorded>')
print('Missing:',sorted(names))
