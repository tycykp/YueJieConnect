"""Only newly connected finder entry points and native container relationships."""
from pathlib import Path
from hashlib import sha256
import json,zipfile,xml.etree.ElementTree as E
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
root=Path(__file__).resolve().parents[1]
apk=root/'captures/device-interconnect-20261108/hyperos4/com.milink.service/base.apk'
assert sha256(apk.read_bytes()).hexdigest()=='4d4ec36791dcc58b12efde494d24d0a5c8c219213433c1f986996c5277f95bd3'
required={
 'Lcom/miui/circulate/ringfind/sc/ui2client/SCClient;':{'isSupportGetStatus(Ljava/lang/String;)Z'},
 'Lcom/miui/circulate/world/sticker/blecard/BleView;':{'j4()V'},
}
found=[]
with zipfile.ZipFile(apk) as a:
 for entry in ['classes2.dex','classes3.dex']:
  data=a.read(entry)
  for cls in DEX(data).get_classes():
   if cls.get_name() not in required:continue
   methods={m.get_name()+m.get_descriptor().replace(' ','') for m in cls.get_methods()}
   expected=required.pop(cls.get_name());assert expected<=methods
   found.append({'class':cls.get_name(),'methods':sorted(expected),'dex_sha256':sha256(data).hexdigest()})
assert not required,required
layouts=[]
base=root/'captures/device-interconnect-20261108/hyperos4/analysis/jadx/com.milink.service/resources/res/layout'
ns='{http://schemas.android.com/apk/res/android}'
for filename in ['circulate_phone_device_card_port.xml','circulate_phone_device_card_land.xml']:
 p=base/filename;r=E.parse(p).getroot();parents={child:parent for parent in r.iter() for child in parent}
 ring=next(n for n in r.iter() if n.get(ns+'id')=='@+id/ringing_view')
 assert parents[ring].get(ns+'id')=='@+id/container'
 assert ring.get(ns+'visibility')=='gone'
 layouts.append({'file':filename,'sha256':sha256(p.read_bytes()).hexdigest(),'container':'container','initial_visibility':'gone'})
out=root/'build/native-pairing-validation/finder-integration-dex.json'
out.write_text(json.dumps({'classes':found,'layouts':layouts,'reused_evidence':'adapters/hyperos4/dex-ui-evidence.json; build/device-finder-validation/verification.json'},indent=2),encoding='utf-8')
print('Verified 2 new methods and both native phone-card container relationships; reused unchanged UI/finder signature records.')
