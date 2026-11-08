"""Read only fixed OEM page labels if FindMyPhone is already foreground; no screenshots/account data."""
import datetime,json,os,re,subprocess,uuid,hashlib
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1];lock=root/'build/coordination-locks/devices.lock'
out=root/'build/device-finder-repair/native-page-prerequisite.json'
lease=dict(task='device-finder-native-page-read',threadId='01a1bbe4-f070-7731-9b98-3abbf216410c',token=uuid.uuid4().hex)
record=dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),command='python -X utf8 scripts/inspect-device-finder-native-page.py',script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),scope='Read-only fixed native labels, no launch/tap/account/private text/screenshot')
def adb(*parts):return subprocess.check_output(['C:/adb/adb.exe','-s','3B161Q00YN200000',*parts],stdin=subprocess.DEVNULL,timeout=15)
owned=False
try:
 if out.exists():raise RuntimeError('Observation already recorded; do not repeat without a changed prerequisite')
 fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY);owned=True
 with os.fdopen(fd,'w',encoding='utf8')as f:json.dump(lease,f)
 raw=adb('shell','dumpsys window').decode('utf8','replace');focus=[l for l in raw.splitlines()if'mCurrentFocus=Window{'in l]
 record['native_find_foreground']=len(focus)==1 and 'com.coloros.findmyphone/'in focus[0]
 if record['native_find_foreground']:
  path='/data/local/tmp/qj-finder-native-prerequisite.xml'
  try:
   adb('shell','uiautomator dump '+path);tree=ET.fromstring(adb('exec-out','cat',path))
  finally:adb('shell','rm -f '+path)
  allowed={'登录','登录账号','请登录','同意并使用','同意','不同意','开启查找','查找设备','设备','我的设备','查找','立即开启','暂不开启','响铃','帮他人查找','其他品牌设备'}
  record['fixed_native_labels']=sorted({n.get('text')for n in tree.iter('node')if n.get('package')=='com.coloros.findmyphone'and n.get('text')in allowed})
 record['result']='observed'
except FileExistsError:record['result']='lock-busy-no-device-command'
except Exception as failure:record['result']='incomplete';record['error']=str(failure)
finally:
 if owned and json.loads(lock.read_text()).get('token')==lease['token']:lock.unlink()
 if not out.exists():out.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(record,ensure_ascii=False,indent=2))
