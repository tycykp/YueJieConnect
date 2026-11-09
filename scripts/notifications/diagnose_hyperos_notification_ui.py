"""Local artificial notification layout observation only; no pairing or remote action."""
import json
from pathlib import Path
import sys
import time
import subprocess
import importlib.util
import uuid
import xml.etree.ElementTree as ET
import io
from PIL import Image

scripts=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(scripts))
spec=importlib.util.spec_from_file_location('notification_actions',scripts/'test-notification-actions-native.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
lock=m.ROOT/'build/coordination-locks/devices.lock'
if m.OWNER not in lock.read_text(encoding='utf-8-sig'):raise RuntimeError('notifications lease required')
serial='b59f8656'
out=m.ROOT/('build/notifications-validation/repair-round3/hyperos-local-layout-'+uuid.uuid4().hex)
out.mkdir(parents=True,exist_ok=True)
record={'scope':'Three local fixed artificial notifications and one native expansion gesture; no pairing, package/NLS policy, action click, or PendingIntent dispatch','steps':[],
        'runner_sha256':m.sha(Path(__file__)),'ui_helper_sha256':m.sha(m.HELPER)}
try:
    m.adb(serial,'push',str(m.HELPER),m.REMOTE_HELPER)
    for identifier in [900005,900006,900007]:
        record['steps'].append(m.fixture(serial,'post',id=identifier,revision=1))
    m.adb(serial,'shell','cmd','statusbar','expand-notifications');time.sleep(.6)
    record['snapshots']={}
    gesture_done=False
    for identifier in [900007,900006,900005]:
        data=m.helper(serial,identifier,'snapshot')
        begin=data.find('<?xml');end=data.find('</hierarchy>',begin)
        if begin<0 or end<0:
            record['snapshots'][str(identifier)]='unavailable'
        else:
            # The device helper has already removed every arbitrary text/content-description.
            xml=data[begin:end+len('</hierarchy>')]
            name=str(identifier)+'-synthetic-row.xml'
            (out/name).write_text(xml,encoding='utf-8')
            record['snapshots'][str(identifier)]=name
            tree=ET.fromstring(xml)
            titles=[n for n in tree.iter('node') if n.get('text')=='越界合成通知 '+str(identifier)]
            if not gesture_done and len(titles)==1:
                selected=m.card(tree,identifier)
                if selected is not None and selected.get('clickable')=='true' and any(n.get('resource-id','').endswith('notification_children_container') for n in list(selected)):
                    m.tap(serial,selected);record['group_tapped']=m.bounds(selected)
                else:
                    x1,y1,x2,y2=m.bounds(titles[0]);x,y=(x1+x2)//2,(y1+y2)//2
                    m.adb(serial,'shell','input','swipe',str(x),str(y),str(x),str(y+260),'450')
                gesture_done=True
                time.sleep(.5)
                data=m.helper(serial,identifier,'snapshot');begin=data.find('<?xml');end=data.find('</hierarchy>',begin)
                if begin>=0 and end>=0:
                    after=data[begin:end+len('</hierarchy>')]
                    (out/'after-gesture.xml').write_text(after,encoding='utf-8')
                    if record.get('group_tapped'):
                        second=ET.fromstring(after)
                        title=next((n for n in second.iter('node') if n.get('text')=='越界合成通知 '+str(identifier)),None)
                        if title is not None:
                            x1,y1,x2,y2=m.bounds(title);x,y=(x1+x2)//2,(y1+y2)//2
                            m.adb(serial,'shell','input','swipe',str(x),str(y),str(x),str(y+260),'450');time.sleep(.5)
                            data=m.helper(serial,identifier,'snapshot');begin=data.find('<?xml');end=data.find('</hierarchy>',begin)
                            if begin>=0 and end>=0:(out/'after-card-gesture.xml').write_text(data[begin:end+len('</hierarchy>')],encoding='utf-8')
                            record['two_pointer_expansion']='native_expand_gesture_sent' in m.helper(serial,identifier,'expand-card');time.sleep(.5)
                            data=m.helper(serial,identifier,'snapshot');begin=data.find('<?xml');end=data.find('</hierarchy>',begin)
                            if begin>=0 and end>=0:
                                final=data[begin:end+len('</hierarchy>')]
                                (out/'after-two-pointer.xml').write_text(final,encoding='utf-8')
                                group=m.card(ET.fromstring(final),identifier)
                                if group is not None and any(n.get('resource-id','').endswith('notification_children_container') for n in list(group)):
                                    crop=m.bounds(group)
                                    pixels=subprocess.check_output(['adb','-s',serial,'exec-out','screencap','-p'],timeout=15)
                                    Image.open(io.BytesIO(pixels)).crop(crop).save(out/'synthetic-group.png')
                                    record['synthetic_group_crop']=crop
finally:
    record['cleanup']={}
    for identifier in [900005,900006,900007]:
        record['cleanup'][str(identifier)]=m.fixture(serial,'remove',id=identifier)
    m.adb(serial,'shell','cmd','statusbar','collapse')
    m.adb(serial,'shell','rm','-f',m.REMOTE_HELPER)
    (out/'result.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'snapshot':record.get('snapshot'),'evidence':str(out.relative_to(m.ROOT))}))
