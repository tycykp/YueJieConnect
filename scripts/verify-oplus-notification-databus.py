"""New bounded DEX scope: real phone/pad switch consumer; never infer a content sink."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import zipfile
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
root = Path(__file__).resolve().parents[1]
apk = root / 'captures/device-interconnect-20261108/coloros17/com.oplus.linker/OPSynergy.apk'
prior = json.loads((root / 'build/notifications-validation/native-samples.json').read_text(encoding='utf-8'))
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
assert digest == prior['oplus']['apk_sha256'], 'OEM sample changed; do not reuse prior evidence.'
wanted = {
    'Lcom/oplus/linker/synergy/bus/databus/DataBusSubscriberBroadcastReceiver;': {
        'dealWithRecivedMacSwitchStateDbs', 'startDialogActivity', 'sendNoticeSyncCmd'},
    'Lcom/oplus/linker/synergy/ui/base/NoticeStateChangeHelper;': {
        'updateToPadDeviceNoticeState', 'updatePeerDeviceNoticeStateByPhoneTopic', 'sendNoticeSyncCmd'},
}
with zipfile.ZipFile(apk) as archive:
    raw = archive.read('classes2.dex')
classes = {}
for cls in DEX(raw).get_classes():
    if cls.get_name() not in wanted:
        continue
    methods = {m.get_name()+m.get_descriptor().replace(' ', ''): [i.get_name()+' '+i.get_output() for i in m.get_instructions()]
               for m in cls.get_methods() if m.get_name() in wanted[cls.get_name()]}
    assert wanted[cls.get_name()] <= {name.split('(')[0] for name in methods}
    classes[cls.get_name()] = methods
assert classes.keys() == wanted.keys()
reader = classes['Lcom/oplus/linker/synergy/bus/databus/DataBusSubscriberBroadcastReceiver;']
receive = '\n'.join(next(v for k,v in reader.items() if k.startswith('dealWithRecivedMacSwitchStateDbs(')))
assert 'Notice_CrossDevice' in receive and '->startDialogActivity(' in receive
dialog = '\n'.join(next(v for k,v in reader.items() if k.startswith('startDialogActivity(')))
assert 'DialogActivity' in dialog and '->startActivity(' in dialog
helper = classes['Lcom/oplus/linker/synergy/ui/base/NoticeStateChangeHelper;']
pad = '\n'.join(next(v for k,v in helper.items() if k.startswith('updateToPadDeviceNoticeState(')))
phone = '\n'.join(next(v for k,v in helper.items() if k.startswith('updatePeerDeviceNoticeStateByPhoneTopic(')))
assert 'Oplus_PadConnect' in pad and 'NoticeState;->OPEN' in pad and 'NoticeState;->CLOSE' in pad
assert 'Oplus_LinkerConnect' in phone and 'NoticeState;->OPEN' in phone and 'NoticeState;->CLOSE' in phone
report = {'utc':datetime.now(timezone.utc).isoformat(), 'command':'python scripts/verify-oplus-notification-databus.py',
          'script_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'apk':str(apk.relative_to(root)),
          'apk_sha256':digest, 'dex':'classes2.dex', 'dex_sha256':hashlib.sha256(raw).hexdigest(), 'result':'passed',
          'classes':classes, 'scope':'Only six newly targeted original-DEX methods. Actual Notice_CrossDevice transport is OPEN/CLOSE switch state and dialog consumer. No notification content source/sink inferred; no device operation or old host test rerun.'}
out=root / 'build/notifications-validation/repair-round3/oplus-databus-boundary.json'
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'result':'passed','evidence':str(out.relative_to(root))}))
