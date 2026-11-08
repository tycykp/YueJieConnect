"""Native UI steps under an already-owned finder window; reuses its real compared session."""
import argparse,datetime,hashlib,io,json,re,subprocess,time
from pathlib import Path
import xml.etree.ElementTree as ET
from PIL import Image
from device_finder_debug import command
ROOT=Path(__file__).resolve().parents[1]
SERIALS=('b59f8656','3B161Q00YN200000')
parser=argparse.ArgumentParser();parser.add_argument('phase',choices=['nodes','select','open_card','image','ring']);parser.add_argument('--window',type=Path,required=True);parser.add_argument('--label',default='');args=parser.parse_args()
if '01a1bbe4-f070-7731-9b98-3abbf216410c' not in (ROOT/'build/coordination-locks/devices.lock').read_text():raise RuntimeError('Finder device lease required')
prior=json.loads((args.window/'result.json').read_text(encoding='utf-8'))
paired=next(step for step in prior['steps']if step.get('op')=='pair'and step.get('session_compared'))
session=paired['states'][SERIALS[0]]['session']
if args.label and not re.fullmatch('[a-z0-9-]{1,32}',args.label):raise RuntimeError('Invalid evidence label')
OUT=args.window/('native-'+args.phase+('-'+args.label if args.label else'')+'.json')
if OUT.exists():raise RuntimeError('This native phase already recorded; no automatic repeat')
record=dict(phase=args.phase,utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
 runner_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),apk_sha256=prior['apk_sha256'],
 session=session,session_compared_in=str(args.window/'result.json'),
 scope='Continuation of same owned compared session after focus-parser repair; no reinstall/restart/pair/direct finder RPC')
def adb(serial,*parts,timeout=15):
 result=subprocess.run(['C:/adb/adb.exe','-s',serial,*parts],capture_output=True,stdin=subprocess.DEVNULL,timeout=timeout)
 if result.returncode:raise RuntimeError('ADB command failed: '+parts[0])
 return result.stdout
def require_ui():
 raw=adb(SERIALS[0],'shell','dumpsys window').decode('utf-8','replace')
 focus=[line for line in raw.splitlines()if'mCurrentFocus=Window{'in line]
 if len(focus)!=1 or 'com.milink.service/'not in focus[0]:raise RuntimeError('Expected native Xiaomi UI not foreground; no capture/tap')
def tree():
 require_ui();path='/data/local/tmp/qj-finder-controls.xml'
 try:
  adb(SERIALS[0],'shell','uiautomator dump '+path)
  return ET.fromstring(adb(SERIALS[0],'exec-out','cat',path))
 finally:adb(SERIALS[0],'shell','rm -f '+path)
def box(node):
 coords=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
 if len(coords)!=4 or coords[2]<=coords[0]or coords[3]<=coords[1]:raise RuntimeError('Invalid native control bounds')
 return coords
def ring_control(root):
 nodes=[n for n in root.iter('node')if n.get('package')=='com.milink.service'and n.get('resource-id','').endswith('/ringing_view')]
 if len(nodes)!=1:raise RuntimeError('Native ring control not unique')
 box(nodes[0]);return nodes[0]
def audio():
 raw=adb(SERIALS[1],'shell','dumpsys audio',timeout=5).decode('utf-8','replace')
 players=[line.strip()for line in raw.splitlines()if re.search(r'(?:uid/pid:|u/pid:|uid:|uid=)\s*10117\b',line)]
 streams={};active=None
 for line in raw.splitlines():
  if re.match(r'\s*- STREAM_(ALARM|MUSIC|SYSTEM|RING):',line):active=line.strip().strip('- :');streams[active]=[]
  elif re.match(r'\s*- STREAM_',line):active=None
  elif active and re.match(r'\s*(Muted:|Min:|Max:|Current:|Devices:)',line):streams[active].append(line.strip())
 modes=[line.strip()for line in raw.splitlines()if re.match(r'\s*(mMode=|mBluetoothScoOn=|mSpeakerphoneOn=|mRingerModeInternal=|mRingerModeExternal=)',line)]
 return dict(oem_players=players,streams=streams,modes=modes)
def is_playing(observation):return any(re.search(r'state:\s*started|state=started',line,re.I)for line in observation['oem_players'])
def safe_nodes(root):
 return [dict(id=n.get('resource-id'),bounds=n.get('bounds'),text=n.get('text','')if n.get('text','')in('响铃','正在响铃','停止响铃','点击停止')else'')
  for n in root.iter('node')if n.get('package')=='com.milink.service'and(any(token in n.get('resource-id','')for token in('ringing_view','ring_find','ring_button'))or n.get('text','')in('响铃','正在响铃','停止响铃','点击停止'))]
def restore_session():
 out={}
 for serial in SERIALS:
  state=command(serial,{'op':'bridge_status'})['bridge']
  if state.get('session')==session:out[serial]=command(serial,{'op':'bridge_stop'})
  else:out[serial]={'result':'not-our-active-session-not-stopped'}
 return out
try:
 # Readiness check uses the existing genuine session; it does not confirm again.
 states={s:command(s,{'op':'bridge_status'})['bridge']for s in SERIALS}
 if any(v.get('phase')!='active'or v.get('session')!=session or'device_finder'not in(v.get('projection')or{}).get('operations',[])for v in states.values()):raise RuntimeError('Existing owned finder session no longer effective')
 if args.phase in ('select','open_card'):
  name=states[SERIALS[0]]['projection']['real']['name'];root=tree()
  nodes=[n for n in root.iter('node')if n.get('package')=='com.milink.service'and n.get('text')==name]
  if len(nodes)!=1:raise RuntimeError('Real projected native card not unique; no tap')
  target=nodes[0]
  if args.phase=='open_card':
   parents={child:parent for parent in root.iter()for child in parent};parent=target
   while parent in parents:
    parent=parents[parent];icons=[n for n in parent.iter('node')if n.get('resource-id','').endswith('/ivItem')]
    if icons:break
   if len(icons)!=1:raise RuntimeError('Real peer native graphic not uniquely associated with title; no tap')
   if parent.get('clickable')!='true':raise RuntimeError('Associated native card surface is not clickable')
   target=parent
  coords=box(target);record['selected_bounds']=coords;record['selected_id']=target.get('resource-id')
  require_ui();adb(SERIALS[0],'shell',f'input tap {(coords[0]+coords[2])//2} {(coords[1]+coords[3])//2}')
 elif args.phase in ('nodes','image'):
  root=tree();record['controls']=safe_nodes(root)
  if args.phase=='image':
   node=ring_control(root);require_ui();raw=adb(SERIALS[0],'exec-out','screencap -p')
   path=args.window/'native-ring-control.png';Image.open(io.BytesIO(raw)).crop(tuple(box(node))).save(path);record['control_image']=str(path)
 elif args.phase=='ring':
  root=tree();node=ring_control(root);coords=box(node);x=(coords[0]+coords[2])//2;y=(coords[1]+coords[3])//2
  record['before_audio']=audio()
  if is_playing(record['before_audio']):raise RuntimeError('Native OEM player already started; no start tap')
  require_ui();started=time.monotonic();adb(SERIALS[0],'shell',f'input tap {x} {y}')
  try:
   time.sleep(.7);record['during_audio']=audio();time.sleep(max(0,2.2-(time.monotonic()-started)))
   if not is_playing(record['during_audio']):raise RuntimeError('Native remote playback not independently observed; no second start tap')
   require_ui();adb(SERIALS[0],'shell',f'input tap {x} {y}');record['stop_tap_seconds']=time.monotonic()-started
   time.sleep(.8);record['after_audio']=audio();record['after_controls']=safe_nodes(tree())
   require_ui();raw=adb(SERIALS[0],'exec-out','screencap -p');path=args.window/'native-control-after-stop.png'
   Image.open(io.BytesIO(raw)).crop(tuple(coords)).save(path);record['native_stop_image']=str(path)
   record['independent_player_start_stop']=is_playing(record['during_audio'])and not is_playing(record['after_audio'])
  finally:
   record['cleanup']=restore_session();time.sleep(.5);record['restored_audio']=audio()
 record['result']='observed-not-automatic-business-verdict'
except Exception as failure:record['result']='failed-no-retry';record['error']=str(failure)
finally:
 OUT.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(record,ensure_ascii=False,indent=2))
