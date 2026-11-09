"""Scoped Xiaomi runtime evidence; --probe invokes one real OEM battery request, never confirms pairing."""
import argparse
import datetime
import json
import subprocess
import time
from pathlib import Path
from debug_control import command

parser=argparse.ArgumentParser()
parser.add_argument('--serial',default='b59f8656')
parser.add_argument('--probe',action='store_true')
args=parser.parse_args()
def adb(*parts):
 return subprocess.check_output(['adb','-s',args.serial,*parts],timeout=20).decode('utf-8','replace')
model=adb('shell','getprop','ro.product.model').strip()
if model!='25091RP04C':raise SystemExit('Refusing a device other than the sampled Xiaomi Pad 8 Pro')
state=command(args.serial,{'op':'bridge_status'})['bridge']
result={'time':datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),
 'model':model,'phase':state['phase'],'local_confirmed':state['local_confirmed'],
 'remote_confirmed':state['remote_confirmed'],'last_error':state['last_error'],
 'operations':state['local']['operations'],'native_probe':'not_requested'}
if args.probe:
 if state['phase']!='active' or state['projection'] is None:
  result['native_probe']='blocked_not_confirmed_or_offline'
 else:
  before=adb('shell','logcat','-d','-v','epoch','-s','YueJieXiaomiTest:I','*:S')
  adb('shell','am','broadcast','-a','io.github.yuejie.connect.XIAOMI_PROFILE_PROBE','-p','com.milink.service')
  deadline=time.monotonic()+15
  result['native_probe']='no_terminal_callback'
  while time.monotonic()<deadline:
   after=adb('shell','logcat','-d','-v','epoch','-s','YueJieXiaomiTest:I','*:S')
   lines=[line for line in after.splitlines() if line not in before.splitlines()]
   terminal=[line for line in lines if any(marker in line for marker in ('native_onResponse','native_onSyncFail','native_timeout','probe_error','peer_offline'))]
   if terminal:
    result['native_probe']='onResponse' if any('native_onResponse' in line for line in terminal) else 'failed'
    result['native_log']=terminal
    break
   time.sleep(.3)
root=Path(__file__).resolve().parents[1]
out=root/'captures/gateway-runtime-20261108/hyperos4-v2'
out.mkdir(parents=True,exist_ok=True)
stamp=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).strftime('%Y%m%d-%H%M%S')
path=out/(f'native-probe-{stamp}.json' if args.probe else 'status.json')
path.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False,indent=2))
if args.probe and result['native_probe']!='onResponse':raise SystemExit(1)
