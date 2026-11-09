"""Read a confirmed real peer, then inspect/click its actual Xiaomi device-center card once."""
import argparse
import datetime
import json
import re
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree
from debug_control import command

parser=argparse.ArgumentParser()
parser.add_argument('--open-peer',action='store_true')
parser.add_argument('--label',default='ui')
args=parser.parse_args()
assert re.fullmatch(r'[a-z0-9_-]{1,40}',args.label)
serial='b59f8656'
def adb(*parts):
 return subprocess.check_output(['adb','-s',serial,*parts],timeout=20)
assert adb('shell','getprop','ro.product.model').decode().strip()=='25091RP04C'
state=command(serial,{'op':'bridge_status'})['bridge']
assert state['phase']=='active' and state['local_confirmed'] and state['remote_confirmed'],'No active confirmed session'
name=state['remote']['name']
adb('shell','am','start','-W','-n','com.milink.service/com.miui.circulate.world.CirculateWorldActivity')
adb('shell','uiautomator','dump','/sdcard/yj-ui.xml')
xml=adb('shell','cat','/sdcard/yj-ui.xml')
tree=ElementTree.fromstring(xml)
matches=[n for n in tree.iter('node') if n.get('text')==name]
assert len(matches)==1,{'expected_peer_cards':1,'actual':len(matches)}
out=Path(__file__).resolve().parents[1]/'captures/gateway-runtime-20261108/hyperos4-ui'
out.mkdir(parents=True,exist_ok=True)
(out/(args.label+'-discovery.xml')).write_bytes(xml)
(out/(args.label+'-discovery.png')).write_bytes(adb('exec-out','screencap','-p'))
if args.open_peer:
 parents={c:p for p in tree.iter() for c in p}
 selected=matches[0]
 while selected.get('clickable')!='true':selected=parents[selected]
 x1,y1,x2,y2=map(int,re.findall(r'\d+',selected.get('bounds')))
 adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
 time.sleep(.8)
 # Read screen pixels without launching a second activity over the native popup.
 (out/(args.label+'-detail.png')).write_bytes(adb('exec-out','screencap','-p'))
result={'time':datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),
 'phase':state['phase'],'real_remote_model':state['remote']['model'],
 'native_peer_card_count':len(matches),'native_card_clicked':args.open_peer,
 'requests_before_click':state['requests_sent']}
(out/(args.label+'-result.json')).write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result,indent=2))
