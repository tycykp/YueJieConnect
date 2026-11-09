"""Exclusive, interactive native repair window. No business success is inferred from navigation.

Each stdin line is JSON: prepare, pair, runtime, status, launch, capture(label),
tap(serial,x,y), back(serial), logs, disconnect, quit. The operator must inspect the
current screenshot before selecting coordinates. Only this window's session is cleaned up.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
from debug_control import command

ROOT=Path(__file__).resolve().parents[1]
DEVICES=('b59f8656','3B161Q00YN200000')
PKG='io.github.yuejie.connect'
TASK='01a1bbe4-a33d-7f83-80db-5442125d938f'
parser=argparse.ArgumentParser()
parser.add_argument('--apk',type=Path,required=True)
parser.add_argument('--build-ledger',type=Path,required=True)
args=parser.parse_args()
apk=args.apk.resolve(); ledger=args.build_ledger.resolve()
if TASK not in (ROOT/'build/coordination-locks/devices.lock').read_text():
    raise RuntimeError('Exclusive native-pairing device lease required')
metadata=json.loads(ledger.read_text(encoding='utf-8-sig'))
expected=metadata['sha256']; revision=metadata['runtime_input_sha']
if hashlib.sha256(apk.read_bytes()).hexdigest()!=expected or not re.fullmatch('[0-9a-f]{64}',revision):
    raise RuntimeError('Candidate metadata mismatch')
out=ROOT/'build/native-pairing-repair'/('device-'+uuid.uuid4().hex)
out.mkdir(parents=True)
record={'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'apk':str(apk),
        'apk_sha256':expected,'runtime_input_sha':revision,'build_ledger':str(ledger),
        'runner_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'steps':[],'business_verdict':'pending original UI and actual native callbacks'}
owned_session=None
started_pair=False
prepared=False

def save():
    (out/'result.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')

def adb(serial,*parts):
    if serial not in DEVICES: raise ValueError('Unknown test device')
    r=subprocess.run(['adb','-s',serial,*map(str,parts)],capture_output=True,stdin=subprocess.DEVNULL,timeout=45)
    if r.returncode: raise RuntimeError('ADB '+str(parts[0])+' failed')
    return r.stdout.decode('utf-8','replace')

def states():
    return {s:command(s,{'op':'bridge_status'})['bridge'] for s in DEVICES}

def launch():
    return {DEVICES[0]:adb(DEVICES[0],'shell','am','start','-W','-n',
                'com.milink.service/com.miui.circulate.world.CirculateWorldActivity'),
            DEVICES[1]:adb(DEVICES[1],'shell','su','-c',
                'am start -W -n com.oplus.linker/.synergy.ui.activity.ConnectPCSettingsActivity')}

def capture(label):
    if not re.fullmatch('[a-z0-9_-]{1,48}',label):raise ValueError('Invalid evidence label')
    paths={}
    for s in DEVICES:require_native_foreground(s)
    for s in DEVICES:
        path=out/(s+'-'+label+'.png')
        if path.exists():raise ValueError('Evidence already exists')
        # Raw full-screen capture may include unrelated notification overlays. Only explicitly
        # requested native-window instrumentation should collect images in future windows.
        raise RuntimeError('Full-screen evidence disabled; use a targeted native-window probe')
        paths[s]=str(path)
    return paths

def require_native_foreground(serial):
    text=adb(serial,'shell','dumpsys','activity','activities')
    resumed=[v for v in text.splitlines() if 'topResumedActivity' in v or 'mResumedActivity' in v]
    expected='com.milink.service/com.miui.circulate.world.CirculateWorldActivity' if serial==DEVICES[0] else 'com.oplus.linker/'
    if not any(expected in line for line in resumed):raise RuntimeError('Foreground is outside the original native test page; no UI action')

def cleanup():
    if owned_session is None and not started_pair:return {}
    result={}
    for s in DEVICES:
        current=command(s,{'op':'bridge_status'})['bridge']
        if current.get('phase') in ('stopped','disconnected'):result[s]={'status':'already_stopped'};continue
        if current.get('session')==owned_session or owned_session is None and started_pair:
            result[s]=command(s,{'op':'bridge_stop'})
        else:result[s]={'status':'not_our_session_not_stopped'}
    return result

print(json.dumps({'window':str(out),'ready':True}),flush=True)
try:
    for line in sys.stdin:
        step={'utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
        record['steps'].append(step)
        try:
            data=json.loads(line); op=data['op'];step['op']=op
            if op=='quit':break
            if op=='prepare':
                if prepared:raise ValueError('Installation is one-shot')
                before=states();step['before']=before
                if any(v['phase'] not in ('stopped','disconnected') for v in before.values()):
                    raise RuntimeError('Existing session; no installation performed')
                prepared=True
                step['devices']={}
                for s in DEVICES:
                    packages=('com.xiaomi.mirror','com.milink.service') if s==DEVICES[0] else ('com.oplus.linker','com.coloros.findmyphone','com.oplus.cast')
                    row=step['devices'][s]={}
                    row['pre_stop']={p:adb(s,'shell','su','-c','am force-stop '+p) for p in packages}
                    row['install']=adb(s,'install','-r',apk)
                    if 'Success' not in row['install']:raise RuntimeError('Install did not report success')
                    path=adb(s,'shell','pm','path',PKG).strip()
                    if not re.fullmatch('package:/data/app/[A-Za-z0-9_=+./~-]+\\.apk',path):raise RuntimeError('Unexpected APK path')
                    row['installed_sha256']=adb(s,'shell','sha256sum',path[8:]).split()[0]
                    if row['installed_sha256']!=expected:raise RuntimeError('Installed APK mismatch')
                    # The pre-update stop alone allowed an old module generation to restart during
                    # install. Stop again after successful PackageManager update, then attest DEX.
                    row['post_stop']={p:adb(s,'shell','su','-c','am force-stop '+p) for p in packages}
                step['launch']=launch()
            elif op=='pair':
                if owned_session is not None:raise ValueError('One session per window')
                current=states();step['before']=current
                if any(v['phase'] not in ('stopped','disconnected') for v in current.values()):raise RuntimeError('Existing session')
                started_pair=True
                match=re.search(r'inet (\d+\.\d+\.\d+\.\d+)/',adb(DEVICES[0],'shell','ip','-4','addr','show','wlan0'))
                if not match:raise RuntimeError('WLAN address unavailable')
                step['listen']=command(DEVICES[0],{'op':'bridge_listen','port':38271})
                step['connect']=command(DEVICES[1],{'op':'bridge_connect','address':match.group(1)+':38271'})
                until=time.monotonic()+18
                while True:
                    current=states();step['handshake']=current
                    if all(v['phase']=='awaiting_confirmation' for v in current.values()):break
                    if time.monotonic()>=until:raise RuntimeError('No bilateral confirmation state')
                    time.sleep(.4)
                a,b=(current[s] for s in DEVICES)
                if not re.fullmatch('[a-f0-9]{64}',a['session']) or not a.get('comparison_code') or a['session']!=b['session'] or a['comparison_code']!=b['comparison_code']:
                    raise RuntimeError('Fresh session/comparison code mismatch; not confirmed')
                owned_session=a['session'];record['session_compared']=True;record['session']=owned_session
                step['comparison_code']=a['comparison_code']
                step['confirm']={s:command(s,{'op':'bridge_confirm','session':owned_session,
                    'operations':['device_profile','battery_status','clipboard_write']}) for s in DEVICES}
                step['after']=states()
            elif op=='runtime':
                step['runtime']={s:command(s,{'op':'bridge_runtime_status'}) for s in DEVICES}
                step['all_reported_current']=all(v.get('expected_revision')==revision and v.get('clients') and all(c['matches'] and c['revision']==revision for c in v['clients']) for v in step['runtime'].values())
            elif op=='status':step['states']=states()
            elif op=='launch':step['launch']=launch()
            elif op=='capture':step['screenshots']=capture(data['label'])
            elif op=='tap':
                s=data['serial'];x=data['x'];y=data['y']
                require_native_foreground(s)
                if type(x)!=int or type(y)!=int or not 0<=x<4096 or not 0<=y<4096:raise ValueError('Invalid observed coordinate')
                step.update(serial=s,x=x,y=y,result=adb(s,'shell','input','tap',x,y))
            elif op=='back':
                require_native_foreground(data['serial']);step['result']=adb(data['serial'],'shell','input','keyevent','4')
            elif op=='disconnect':step['cleanup']=cleanup();step['states']=states()
            else:raise ValueError('Unknown controlled command')
            step['result_status']='command_completed_not_business_verdict'
        except Exception as failure:
            step['result_status']='failed_without_retry';step['error']=str(failure)
        save();print(json.dumps(step,ensure_ascii=False),flush=True)
finally:
    record['cleanup']=cleanup()
    save()
    print(json.dumps({'closed':str(out/'result.json')}),flush=True)
