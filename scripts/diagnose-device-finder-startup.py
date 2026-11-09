"""One native host launch after the scope/runtime fix; no pairing, grants, restart or ringing."""
import datetime,hashlib,json,os,re,subprocess,time,uuid
from pathlib import Path
from debug_control import command
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/device-finder-repair/startup-code20.json'
LOCK=ROOT/'build/coordination-locks/devices.lock'
META=ROOT/'build/native-pairing-validation/v20-runtime-candidate-apk.json'
metadata=json.loads(META.read_text(encoding='utf-8-sig'))
record=dict(command='python -X utf8 scripts/diagnose-device-finder-startup.py',
 utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
 apk_sha256=metadata['sha256'],runtime_revision=metadata['runtime_input_sha'],
 scope='Code20 unchanged native receiver initialization/registration only; newest sender/UI/Binding not in this APK. No pair/grant/playback.')
lease=dict(task='device-finder-startup',threadId='01a1bbe4-f070-7731-9b98-3abbf216410c',lease=uuid.uuid4().hex,utc=record['utc'])
owned=False
def adb(*parts,absent_ok=False):
 r=subprocess.run(['C:/adb/adb.exe','-s','3B161Q00YN200000',*parts],capture_output=True,timeout=25)
 record.setdefault('commands',[]).append(dict(args=parts,exit_code=r.returncode))
 if r.returncode and not(absent_ok and r.returncode==1):raise RuntimeError('ADB command failed: '+parts[0])
 return r.stdout.decode('utf-8','replace')
try:
 if OUT.exists():raise RuntimeError('This startup observation already exists; do not automatically repeat')
 fd=os.open(LOCK,os.O_CREAT|os.O_EXCL|os.O_WRONLY);owned=True
 with os.fdopen(fd,'w',encoding='utf-8') as stream:json.dump(lease,stream)
 paths=adb('shell','pm path io.github.yuejie.connect').splitlines()
 if len(paths)!=1 or not re.fullmatch(r'package:/data/app/[A-Za-z0-9_=+./~-]+\.apk',paths[0]):raise RuntimeError('Unexpected module APK path')
 installed=adb('shell','sha256sum '+paths[0][8:]).split()[0];record['installed_sha256']=installed
 if installed!=metadata['sha256']:raise RuntimeError('Installed module differs from this observation input')
 record['native_launch']=adb('shell','am start -W -n com.coloros.findmyphone/com.oplus.findphone.client.map.FindMainActivity')
 time.sleep(2)
 runtime=command('3B161Q00YN200000',{'op':'bridge_runtime_status'})
 record['finder_runtime']={k:v for k,v in runtime.items() if k in ('status','expected_revision')}
 record['finder_runtime']['clients']=[c for c in runtime.get('clients',[]) if c.get('package')=='com.coloros.findmyphone']
 state=command('3B161Q00YN200000',{'op':'bridge_status'})['bridge']
 record['phase']=state.get('phase');record['local_operations']=state.get('local',{}).get('operations',[])
 files=adb('shell',"su -c 'ls /data/adb/lspd/log'").splitlines()
 modules=[f for f in files if re.fullmatch(r'modules[^/]*\.log',f)]
 record['native_initialization_logs']=[]
 for filename in modules:
  raw=adb('shell',"su -c 'cat /data/adb/lspd/log/"+filename+"'")
  record['native_initialization_logs'].extend(line for line in raw.splitlines() if re.search(r'finder_binding_|native_sender_|native_local_finder_ui_|module_loaded .*process=com.coloros.findmyphone',line))
 clients=record['finder_runtime']['clients']
 record['current_finder_runtime']=bool(clients) and all(c.get('matches') and c.get('revision')==metadata['runtime_input_sha'] for c in clients)
 record['receiver_registered']='device_finder' in record['local_operations']
 record['result']='initialized-and-registered' if record['current_finder_runtime'] and record['receiver_registered'] else 'not-ready'
except FileExistsError:
 record['result']='lock-busy-no-device-commands'
except Exception as failure:
 record['result']='incomplete';record['error']=str(failure)
finally:
 if owned and json.loads(LOCK.read_text(encoding='utf-8')).get('lease')==lease['lease']:LOCK.unlink()
 if not OUT.exists():OUT.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(record,ensure_ascii=False,indent=2))
