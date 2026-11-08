"""Original milink DEX proof for the newly identified Android-pad notification path."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import zipfile
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
root=Path(__file__).resolve().parents[1]
manifest=json.loads((root/'captures/device-interconnect-20261108/hyperos4/manifest.json').read_text(encoding='utf-8'))
sample=next(p for p in manifest['packages'] if p['package']=='com.milink.service')['apks'][0]
apk=root/'captures/device-interconnect-20261108/hyperos4/com.milink.service/base.apk'
digest=hashlib.sha256(apk.read_bytes()).hexdigest()
assert digest==sample['sha256']
base='Lcom/xiaomi/dist/notification/'
wanted={
    base+'listener/NotifTransListenerService;':{'onNotificationPosted'},
    base+'listener/handle/NotificationHandler;':{'transNotificationToNearby','isDeviceSupported'},
    base+'listener/trans/NotificationPostRequest;':{'transNotification'},
    base+'listener/trans/NotificationPostResponse;':{'transNotification'},
    base+'common/data/NotificationMessage;':{'getSenderDeviceId','getPackageName','getNotificationKey','getNotificationId','getMessageNumber','getDeviceName','getTitle','getContent','getTimestamp','getCategory','getVerifyCode','getFocusParam'},
    base+'common/data/NotificationMessage$Builder;':{'<init>','build','setSenderDeviceId','setTargetDeviceId','setPackageName','setNotificationKey','setNotificationId','setMessageNumber','setDeviceName','setTitle','setContent','setTimestamp','setCategory','setVerifyCode','setFocusParam'},
    base+'trans/client/receive/NotificationProcessor;':{'getInstance','processorNotification','generateNotificationData','getNotificationShowType'},
    base+'handler/NotificationPresenter;':{'cancelNotification','handleNotification'},
    base+'handler/NotificationController;':{'showNotification','cancelNotification'},
    base+'common/setting/NotificationTransSetting;':{'isPadSwitchEnabled','enableNotifListenerService'},
    base+'common/utils/NearbyDeviceHelper;':{'isNearbyWlanSupported'},
}
with zipfile.ZipFile(apk) as archive:raw=archive.read('classes3.dex')
classes={}
for cls in DEX(raw).get_classes():
    if cls.get_name() not in wanted:continue
    all_methods={m.get_name()+m.get_descriptor().replace(' ',''):m for m in cls.get_methods()}
    methods={name:[i.get_name()+' '+i.get_output() for i in method.get_instructions()] for name,method in all_methods.items() if method.get_name() in wanted[cls.get_name()]}
    assert wanted[cls.get_name()]<={name.split('(')[0] for name in methods}
    classes[cls.get_name()]={'methods':methods,'all_signatures':list(all_methods),'fields':[f.get_name()+':'+f.get_descriptor() for f in cls.get_fields()]}
assert classes.keys()==wanted.keys()
def code(cls,name,descriptor=''):
    rows=[v for k,v in classes[base+cls+';']['methods'].items() if k.startswith(name+'(') and (not descriptor or descriptor in k)]
    assert len(rows)==1,(cls,name,descriptor)
    return '\n'.join(rows[0])
assert '->handleNotificationTrans(' in code('listener/NotifTransListenerService','onNotificationPosted')
assert '->buildNotificationMessage(' in code('listener/handle/NotificationHandler','transNotificationToNearby')
post=code('listener/trans/NotificationPostRequest','transNotification','PublisherManager$IPublishResult;')
assert 'noti_post' in post and 'trans' in post and '->publish(' in post
assert '->processorNotification(' in code('listener/trans/NotificationPostResponse','transNotification')
processor=code('trans/client/receive/NotificationProcessor','processorNotification','(Lcom/xiaomi/dist/notification/common/data/NotificationMessage;)V')
assert '->isPadSwitchEnabled(' in processor and '->isMirrorMainScreen(' in processor and '->handleNotification(' in processor
assert 'targetDeviceId:Ljava/lang/String;' in classes[base+'common/data/NotificationMessage;']['fields']
assert not any(name.startswith('getTargetDeviceId(') for name in classes[base+'common/data/NotificationMessage;']['all_signatures'])
assert '->notify(' in code('handler/NotificationController','showNotification')
assert '->cancel(' in code('handler/NotificationController','cancelNotification')
assert 'pref_key_connectivity_share_notification_pad_receive_state' in code('common/setting/NotificationTransSetting','isPadSwitchEnabled')
for text in ('isKeyguardLocked','isInteractive','getDeviceSwitch','getSubscriptionAppInfoList'):
    assert text in code('listener/handle/NotificationHandler','isDeviceSupported')
apk_manifest=root/'captures/device-interconnect-20261108/hyperos4/analysis/jadx/com.milink.service/resources/AndroidManifest.xml'
xml=__import__('xml.etree.ElementTree',fromlist=['ElementTree']).parse(apk_manifest)
android='{http://schemas.android.com/apk/res/android}'
listener=next(n for n in xml.iter('service') if n.get(android+'name')=='com.xiaomi.dist.notification.listener.NotifTransListenerService')
assert listener.get(android+'enabled')=='false' and listener.get(android+'process')=='com.milink.crossdeviceservice'
sources={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (root/'android/module/src/main/java/io/github/qiangjian/connect').glob('XiaomiNotificationDistributed*.java')}
out=root/'build/notifications-validation/repair-round3/distributed/native-signatures.json'
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps({'utc':datetime.now(timezone.utc).isoformat(),'command':'python scripts/verify-xiaomi-distributed-notification.py','result':'passed',
    'apk':str(apk.relative_to(root)),'apk_sha256':digest,'dex':'classes3.dex','dex_sha256':hashlib.sha256(raw).hexdigest(),
    'script_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'classes':classes,'sources':sources,
    'listener':{'enabled':False,'process':'com.milink.crossdeviceservice'},
    'scope':'Original DEX proof of actual Android-pad source/message/sink, original subscription/lock/screen/app/pad gates intact. Native callback not fabricated. No source/sink integration or device validation claimed.'},ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'result':'passed','evidence':str(out.relative_to(root))}))
