"""Deploy the current debug build and check real bilateral relay executor readiness.

Only the app-relay/file grants are selected. No notification/clipboard content is read.
The validation session is stopped on every exit; OEM application data is preserved.
"""
from pathlib import Path
import concurrent.futures, hashlib, json, os, re, subprocess, time, sys
from debug_control import command

ROOT=Path(__file__).resolve().parents[1]
APK=ROOT/'android/module/build/outputs/apk/debug/module-debug.apk'
OUT=ROOT/'build/production-integration'
DEVICES=['b59f8656','3B161Q00YN200000']
PKG='io.github.yuejie.connect'
def adb(serial,*args):
    result=subprocess.run(['adb','-s',serial,*args],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
    if result.returncode or 'Error:' in result.stdout or 'Exception' in result.stderr:
        raise RuntimeError('ADB failed: '+args[0]+' '+result.stderr[-400:])
    return result.stdout
def states():
    with concurrent.futures.ThreadPoolExecutor() as pool:
        return dict(zip(DEVICES,pool.map(lambda s:command(s,{'op':'bridge_status'})['bridge'],DEVICES)))
def until(test,seconds):
    deadline=time.monotonic()+seconds
    while True:
        value=states()
        if test(value):return value
        if time.monotonic()>=deadline:raise RuntimeError('Timed out waiting for native integration readiness')
        time.sleep(.5)
def main():
    OUT.mkdir(parents=True,exist_ok=True)
    lock=ROOT/'build/coordination-locks/devices.lock';lock.parent.mkdir(parents=True,exist_ok=True)
    record={'apk_sha256':hashlib.sha256(APK.read_bytes()).hexdigest(),'result':'failed'}
    active=False
    with lock.open('x',encoding='utf-8') as lease:
        lease.write('production integration '+str(os.getpid()));lease.flush()
        try:
            initial=states()
            if any(s.get('phase') not in ('stopped','disconnected') for s in initial.values()):raise RuntimeError('An existing session is active; refusing to replace it')
            for serial in DEVICES:
                packages=['com.xiaomi.mirror','com.milink.service'] if serial==DEVICES[0] else ['com.oplus.linker']
                for package in packages:adb(serial,'shell','su','-c','am force-stop '+package)
                if '--installed' not in sys.argv and 'Success' not in adb(serial,'install','-r',str(APK)):raise RuntimeError('Installation did not succeed')
                installed=adb(serial,'shell','pm','path',PKG).strip()
                if not re.fullmatch(r'package:/data/app/[A-Za-z0-9_=+./~-]+\.apk',installed):raise RuntimeError('Unexpected installed package path')
                if adb(serial,'shell','sha256sum',installed[8:]).split()[0]!=record['apk_sha256']:raise RuntimeError('Installed APK mismatch')
                for package in packages:adb(serial,'shell','su','-c','am force-stop '+package)
            adb(DEVICES[0],'shell','am','start','-W','-n','com.milink.service/com.miui.circulate.world.CirculateWorldActivity')
            adb(DEVICES[1],'shell',"su -c 'am start -W -n com.oplus.linker/.synergy.ui.activity.ConnectPCSettingsActivity'")
            ip=re.search(r'inet (\d+\.\d+\.\d+\.\d+)/',adb(DEVICES[0],'shell','ip','-4','addr','show','wlan0'))
            if not ip:raise RuntimeError('Xiaomi WLAN unavailable')
            active=True
            if command(DEVICES[0],{'op':'bridge_listen','port':38271}).get('status')!='ok':raise RuntimeError('Listen failed')
            if command(DEVICES[1],{'op':'bridge_connect','address':ip.group(1)+':38271'}).get('status')!='ok':raise RuntimeError('Connect failed')
            handshake=until(lambda s:all(v.get('phase')=='awaiting_confirmation' for v in s.values()),20)
            a,b=(handshake[s] for s in DEVICES)
            if a['session']!=b['session'] or not a['comparison_code'] or a['comparison_code']!=b['comparison_code']:raise RuntimeError('Bilateral comparison mismatch')
            for serial in DEVICES:
                if command(serial,{'op':'bridge_confirm','session':a['session'],'operations':['device_profile','battery_status','app_relay','file_transfer']}).get('status')!='ok':raise RuntimeError('Confirmation failed')
            record['active']=until(lambda s:all(v.get('phase')=='active' for v in s.values()),10)
            try:
                ready=until(lambda s:all('app_relay' in v.get('projection',{}).get('operations',[]) for v in s.values()),35)
                record['relay_ready']={s:ready[s]['projection']['operations'] for s in DEVICES}
                deadline=time.monotonic()+15
                acknowledged=set()
                while len(acknowledged)<len(DEVICES):
                    for serial in DEVICES:
                        runtime=command(serial,{'op':'bridge_runtime_status'})
                        pids={str(c['pid']) for c in runtime.get('clients',[]) if c.get('matches')}
                        logs=adb(serial,'shell','logcat','-d','-v','brief','-s','YueJieRelay:I','*:S')
                        if any(pid in pids for pid in re.findall(r'YueJieRelay\(\s*(\d+)\).*data_channel_open',logs)):acknowledged.add(serial)
                    if time.monotonic()>=deadline:raise RuntimeError('Native DATA channel has not received a bound remote acknowledgement')
                    if len(acknowledged)<len(DEVICES):time.sleep(.5)
                record['logical_data_acknowledged']=sorted(acknowledged);record['result']='passed'
            finally:
                record['last_status']=states()
                record['runtimes']={s:command(s,{'op':'bridge_runtime_status'}) for s in DEVICES}
                for serial in DEVICES:
                    lines='\n'.join(adb(serial,'shell','logcat','-d','-v','brief','-s','YueJie:I','YueJieClient:I','YueJieRelay:I','*:S').splitlines()[-250:])
                    (OUT/(serial+'-integration.log')).write_text(lines,encoding='utf-8')
        except Exception as error:
            record['error']=str(error);raise
        finally:
            if active and not ('--keep-session' in sys.argv and record['result']=='passed'):
                record['cleanup']={}
                for serial in DEVICES:
                    try:record['cleanup'][serial]=command(serial,{'op':'bridge_stop'})
                    except Exception as error:record['cleanup'][serial]={'error':type(error).__name__}
            (OUT/'relay-device.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
            lease.close();lock.unlink(missing_ok=True)
    print('PASS: current APK, bilateral authentication, real app-relay executor registration; '+('session retained for native UI verification.' if '--keep-session' in sys.argv else 'validation session stopped.'))
if __name__=='__main__':main()
