"""Exclusive original-UI finder window. No direct finder start RPC, install, or cloud data access."""
import argparse,datetime,hashlib,io,json,re,subprocess,sys,time,uuid
from pathlib import Path
import xml.etree.ElementTree as ET
from PIL import Image
from device_finder_debug import command
ROOT=Path(__file__).resolve().parents[1]
SERIALS=('b59f8656','3B161Q00YN200000')
TASK='01a1bbe4-f070-7731-9b98-3abbf216410c'
parser=argparse.ArgumentParser();parser.add_argument('--apk',type=Path,required=True);parser.add_argument('--metadata',type=Path,required=True);args=parser.parse_args()
if TASK not in (ROOT/'build/coordination-locks/devices.lock').read_text():raise RuntimeError('Finder device lease required')
metadata=json.loads(args.metadata.read_text(encoding='utf-8-sig'));apk_sha=hashlib.sha256(args.apk.read_bytes()).hexdigest()
if apk_sha!=metadata['sha256']:raise RuntimeError('APK metadata mismatch')
OUT=ROOT/'build/device-finder-repair'/('native-'+uuid.uuid4().hex);OUT.mkdir(parents=True)
record=dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),apk=str(args.apk),apk_sha256=apk_sha,
 runtime_revision=metadata['runtime_input_sha'],runner_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),steps=[],
 scope='Native sender UI only. ADB/debug API used only for session/status/runtime/cleanup; no direct device_finder start/stop RPC.')
owned_session=None;created=False;paired=False;ring_attempted=False;last_nodes={};target_serial=SERIALS[0]
def adb(serial,*parts,timeout=20):
 r=subprocess.run(['C:/adb/adb.exe','-s',serial,*map(str,parts)],capture_output=True,stdin=subprocess.DEVNULL,timeout=timeout)
 if r.returncode:raise RuntimeError('ADB '+str(parts[0])+' failed')
 return r.stdout.decode('utf-8','replace')
def states():return {s:command(s,{'op':'bridge_status'})['bridge'] for s in SERIALS}
def safe_state(state):return dict(phase=state.get('phase'),session=state.get('session'),local_operations=state.get('local',{}).get('operations',[]),effective_operations=(state.get('projection')or{}).get('operations',[]))
def current_package(serial):
 text=adb(serial,'shell','dumpsys window')
 matches=re.findall(r'mCurrentFocus=Window\{[^}]*\s([a-zA-Z0-9_.]+)/(?:[a-zA-Z0-9_.$]+)\}',text)
 return matches[0]if len(matches)==1 else''
def require_native(serial):
 pkg='com.milink.service' if serial==SERIALS[0] else 'com.coloros.findmyphone'
 if current_package(serial)!=pkg:raise RuntimeError('Expected original finder UI not foreground; no capture/tap performed')
 return pkg
def ui_nodes(serial):
 pkg=require_native(serial);temporary='/data/local/tmp/yj-finder-native.xml'
 try:
  adb(serial,'shell','uiautomator dump '+temporary)
  root=ET.fromstring(adb(serial,'exec-out','cat',temporary))
 finally:adb(serial,'shell','rm -f '+temporary)
 hits=[]
 for node in root.iter('node'):
  rid=node.get('resource-id','');text=node.get('text','');desc=node.get('content-desc','')
  if node.get('package')!=pkg:continue
 if any(token in rid for token in ('ringing_view','ring_find','ringing','ring_button','key_function_ring')) or text in ('响铃','停止响铃','正在响铃','点击停止','附近已配对的设备'):
   hits.append(dict(id=rid,bounds=node.get('bounds'),clickable=node.get('clickable'),text=text if len(text)<24 else '',description=desc if len(desc)<24 else ''))
 last_nodes[serial]=hits;return hits
def bounds(text):
 values=list(map(int,re.findall(r'\d+',text or '')))
 if len(values)!=4 or values[2]<=values[0] or values[3]<=values[1]:raise RuntimeError('Invalid native control bounds')
 return values
def cropped_control(serial,label,node):
 require_native(serial);box=bounds(node['bounds']);raw=subprocess.check_output(['C:/adb/adb.exe','-s',serial,'exec-out','screencap','-p'],timeout=10)
 shot=Image.open(io.BytesIO(raw));path=OUT/(label+'.png');shot.crop(tuple(box)).save(path);return str(path)
def audio(serial):
 raw=adb(serial,'shell','dumpsys audio',timeout=5)
 # Only system audio state and this exact OEM UID's player records; never retain media metadata or other apps.
 uid='10117' if serial==SERIALS[1] else None
 players=[line.strip() for line in raw.splitlines() if uid and re.search(r'(?:uid/pid:|u/pid:|uid:|uid=)\s*'+uid+r'\b',line)]
 modes=[line.strip() for line in raw.splitlines() if re.search(r'^\s*(mMode=|mBluetoothScoOn=|mSpeakerphoneOn=|mRingerModeInternal=|mRingerModeExternal=|mode:)',line)]
 streams={};active=None
 for line in raw.splitlines():
  if re.match(r'\s*- STREAM_(ALARM|MUSIC|SYSTEM|RING):',line):active=line.strip().strip('- :');streams[active]=[]
  elif re.match(r'\s*- STREAM_',line):active=None
  elif active and re.match(r'\s*(Muted:|Min:|Max:|Current:|Devices:)',line):streams[active].append(line.strip())
 return dict(oem_player_records=players,audio_modes=modes,streams=streams)
def resources(serial):
 raw=adb(serial,'shell','dumpsys media.camera',timeout=5)
 torch=[line.strip() for line in raw.splitlines() if re.match(r'\s*(Torch status:|Torch mode status:)',line)]
 power=adb(serial,'shell','dumpsys power',timeout=5)
 wake=[line.strip() for line in power.splitlines() if re.match(r'\s*(mWakefulness=|mWakefulnessChanging=)',line)]
 return dict(audio=audio(serial),torch=torch,screen=wake)
def cleanup():
 if not created:return {}
 result={}
 for serial in SERIALS:
  state=command(serial,{'op':'bridge_status'})['bridge']
  if owned_session and state.get('session')==owned_session or state.get('phase') in ('stopped','disconnected','listening','connecting','awaiting_confirmation'):
   result[serial]=command(serial,{'op':'bridge_stop'})
  else:result[serial]={'result':'different-active-session-not-stopped'}
 return result
def save():(OUT/'result.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(dict(window=str(OUT),ready=True)),flush=True)
try:
 for line in sys.stdin:
  step=dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat());record['steps'].append(step)
  try:
   data=json.loads(line);op=data['op'];step['op']=op
   if op=='quit':break
   if op=='verify':
    step['installed']={}
    for serial in SERIALS:
     paths=adb(serial,'shell','pm path io.github.yuejie.connect').strip().splitlines()
     if len(paths)!=1 or not re.fullmatch(r'package:/data/app/[A-Za-z0-9_=+./~-]+\.apk',paths[0]):raise RuntimeError('Unexpected installed APK path')
     actual=adb(serial,'shell','sha256sum '+paths[0][8:]).split()[0];step['installed'][serial]=actual
     if actual!=apk_sha:raise RuntimeError('Installed APK differs; this window will not install/restart')
    step['states']={s:safe_state(v) for s,v in states().items()}
   elif op=='pair':
    if paired:raise RuntimeError('One fresh session per window')
    before=states();step['before']={s:safe_state(v) for s,v in before.items()}
    if any(v.get('phase')not in ('stopped','disconnected')for v in before.values()):raise RuntimeError('Existing session; not disturbed')
    paired=True
    match=re.search(r'inet (\d+\.\d+\.\d+\.\d+)/',adb(SERIALS[0],'shell','ip -4 addr show wlan0'))
    if not match:raise RuntimeError('WLAN address unavailable')
    step['listen']=command(SERIALS[0],{'op':'bridge_listen','port':38279});created=True
    step['connect']=command(SERIALS[1],{'op':'bridge_connect','address':match.group(1)+':38279'})
    until=time.monotonic()+18
    while True:
     current=states()
     if all(v.get('phase')=='awaiting_confirmation' for v in current.values()):break
     if time.monotonic()>=until:raise RuntimeError('No fresh bilateral confirmation state')
     time.sleep(.4)
    a,b=(current[s]for s in SERIALS)
    if not re.fullmatch('[0-9a-f]{64}',a.get('session',''))or a['session']!=b.get('session')or not a.get('comparison_code')or a['comparison_code']!=b.get('comparison_code'):
     raise RuntimeError('Session/comparison code mismatch; not confirmed')
    owned_session=a['session'];step['comparison_code']=a['comparison_code'];step['session_compared']=True
    step['confirm']={s:command(s,{'op':'bridge_confirm','session':owned_session,'operations':['device_profile','battery_status','device_finder']})for s in SERIALS}
    until=time.monotonic()+12
    while True:
     current=states()
     if all(v.get('phase')=='active'and'device_finder'in(v.get('projection')or{}).get('operations',[])for v in current.values()):break
     if time.monotonic()>=until:raise RuntimeError('Finder effective capability not ready; no ring triggered')
     time.sleep(.5)
    step['states']={s:safe_state(v)for s,v in current.items()}
   elif op=='runtime':
    step['runtime']={s:command(s,{'op':'bridge_runtime_status'})for s in SERIALS}
    expected=metadata['runtime_input_sha']
    for serial,pkg in ((SERIALS[0],'com.milink.service'),(SERIALS[1],'com.coloros.findmyphone')):
     clients=[c for c in step['runtime'][serial].get('clients',[])if c.get('package')==pkg]
     if not clients or not all(c.get('matches')and c.get('revision')==expected for c in clients):raise RuntimeError('Relevant OEM runtime not current')
   elif op=='status':step['states']={s:safe_state(v)for s,v in states().items()}
   elif op=='launch':
    serial=data.get('serial',SERIALS[0]);target_serial=serial
    component='com.milink.service/com.miui.circulate.world.CirculateWorldActivity'if serial==SERIALS[0]else'com.coloros.findmyphone/com.oplus.findphone.client.map.FindMainActivity'
    step['launch']=adb(serial,'shell','am start -W -n '+component)
   elif op=='nodes':step['nodes']=ui_nodes(data.get('serial',target_serial))
   elif op=='select_peer':
    serial=data.get('serial',target_serial);pkg=require_native(serial)
    bridge=command(serial,{'op':'bridge_status'})['bridge'];peer=((bridge.get('projection')or{}).get('real')or{}).get('name')
    if not peer:raise RuntimeError('No real selected peer name')
    temporary='/data/local/tmp/yj-finder-native.xml'
    try:
     require_native(serial);adb(serial,'shell','uiautomator dump '+temporary)
     tree=ET.fromstring(adb(serial,'exec-out','cat',temporary))
    finally:adb(serial,'shell','rm -f '+temporary)
    hits=[n for n in tree.iter('node')if n.get('package')==pkg and n.get('text')==peer]
    if len(hits)!=1:raise RuntimeError('Real peer not uniquely present in original native UI; no tap')
    parents={child:parent for parent in tree.iter()for child in parent};target=hits[0]
    while target in parents and target.get('clickable')!='true':target=parents[target]
    if target.get('clickable')!='true':raise RuntimeError('Native peer clickable card surface unavailable')
    box=bounds(target.get('bounds'));require_native(serial)
    step['selected_resource_id']=target.get('resource-id');step['selected_bounds']=box
    step['tap']=adb(serial,'shell',f'input tap {(box[0]+box[2])//2} {(box[1]+box[3])//2}')
   elif op=='tap':
    serial=data.get('serial',target_serial);require_native(serial)
    x,y=data['x'],data['y']
    if type(x)is not int or type(y)is not int or not 0<=x<4096 or not 0<=y<4096:raise RuntimeError('Invalid observed coordinate')
    step['tap']=adb(serial,'shell',f'input tap {x} {y}')
   elif op=='control_image':
    serial=data.get('serial',target_serial);nodes=ui_nodes(serial);index=data.get('index',0)
    step['image']=cropped_control(serial,data['label'],nodes[index])
   elif op=='ring_short':
    if ring_attempted:raise RuntimeError('One native ring scenario per window, no automatic retry')
    serial=data.get('serial',target_serial);other=SERIALS[1]if serial==SERIALS[0]else SERIALS[0]
    state=states()
    if any(v.get('session')!=owned_session or v.get('phase')!='active'or'device_finder'not in(v.get('projection')or{}).get('operations',[])for v in state.values()):raise RuntimeError('Current owned finder capability/session required')
    nodes=ui_nodes(serial);matching=[n for n in nodes if n['id'].endswith('/ringing_view')]
    if len(matching)!=1:raise RuntimeError('Original native ring control not uniquely located')
    node=matching[0];box=bounds(node['bounds']);x=(box[0]+box[2])//2;y=(box[1]+box[3])//2
    step['before_resources']=resources(other);step['before_control']=node;ring_attempted=True
    if any(re.search(r'state:\s*started|state=started',line,re.I)for line in step['before_resources']['audio']['oem_player_records']):raise RuntimeError('Original OEM player already active; no ringing started')
    started=time.monotonic();require_native(serial);adb(serial,'shell',f'input tap {x} {y}')
    try:
     time.sleep(.7);step['during_audio']=audio(other)
     time.sleep(max(0,2.2-(time.monotonic()-started)))
     if not any(re.search(r'state:\s*started|state=started',line,re.I)for line in step['during_audio']['oem_player_records']):raise RuntimeError('Native remote playback not independently observed; no second start tap')
     require_native(serial);step['native_stop_tap']=adb(serial,'shell',f'input tap {x} {y}')
     step['native_stop_at_seconds']=time.monotonic()-started
     time.sleep(.8);step['after_audio']=audio(other);step['after_nodes']=ui_nodes(serial)
     step['after_control_image']=cropped_control(serial,'after-native-stop',node)
    finally:
     # Always withdraw this test's own session; this is cleanup, not a successful native stop verdict.
     step['cleanup']=cleanup()
     time.sleep(.6);step['restored_resources']=resources(other)
   elif op=='disconnect':step['cleanup']=cleanup()
   else:raise RuntimeError('Unknown controlled finder command')
   step['result']='command-completed-not-business-verdict'
  except Exception as failure:step['result']='failed-no-automatic-retry';step['error']=str(failure)
  save();print(json.dumps(step,ensure_ascii=False),flush=True)
finally:
 record['cleanup']=cleanup();save();print(json.dumps({'closed':str(OUT/'result.json')}),flush=True)
