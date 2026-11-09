"""Pin this native-only local UI reflection surface and original layouts, without baseline reruns."""
from pathlib import Path
import hashlib,json,zipfile
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
root=Path(__file__).resolve().parents[1]
apk=root/'captures/device-finder-20261108/coloros17/com.coloros.findmyphone/FindMyPhone.apk'
apk_sha=hashlib.sha256(apk.read_bytes()).hexdigest()
assert apk_sha=='6e24b00e3c8689783dcd9b61287cc99bb6ab2dd688af915a7ea794666963596d'
out=root/'build/device-finder-repair/ui-surface.json'
data='Lcom/oplus/findphone/client/device/devicedetail/DeviceDetailData;'
local='Lcom/oplus/findphone/client/device/devicedetail/a;'
view='Lcom/oplus/findphone/client/device/devicedetail/b;'
app='Lcom/oplus/FindMyPhoneApplication;'
required={
 'Landroidx/recyclerview/widget/z;':({'c(I)Ljava/lang/Object;','d(Ljava/util/List;)V'},set()),
 'Lwc/e;':({'onBindViewHolder(Landroidx/recyclerview/widget/RecyclerView$C;I)V'},set()),
 'Lwc/e$a;':(set(),{'a:Landroid/widget/ImageView;'}),
 'Lwc/e$c;':(set(),{'a:Lcom/coui/appcompat/textview/COUITextView;','b:Lcom/coui/appcompat/textview/COUITextView;'}),
 'Lhc/i$b;':({'<init>()V'},{'a:Lcom/oplus/findphone/client/device/DeviceListItem;'}),
 'Lhc/i$e;':({'<init>()V'},{'a:I'}),
 'Lcom/oplus/findphone/client/map/D;':({'onCreateView(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroid/view/View;',
 'n(Lcom/oplus/findphone/client/device/DeviceListItem;)V'},{'c:Lwc/e;'}),
 'Lcom/oplus/findphone/client/map/N;':({'onClick(Landroid/view/View;)V'},{'a:I','b:Ljava/lang/Object;','c:Ljava/lang/Object;'}),
 'Lcom/oplus/findphone/client/map/t;':({'onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V','onDestroyView()V'},
 {'m:Lcom/oplus/findphone/client/ViewModel/DeviceDetailViewModel;'}),
 'Lcom/oplus/findphone/client/ViewModel/DeviceDetailViewModel;':({'e()V','onCleared()V'},
 {'a:Landroidx/lifecycle/MutableLiveData;','b:Landroidx/lifecycle/MutableLiveData;','c:Landroidx/lifecycle/MutableLiveData;',
 'd:Lcom/oplus/findphone/client/map/z;','h:Ljava/lang/String;'}),
 'Lcom/oplus/findphone/client/map/z;':(set(),{'a:'+local}),
 local:({'<init>()V'},{'b:I'}),
 view:(set(),{'h:Ljava/util/ArrayList;','i:Ljava/util/ArrayList;','j:Ljava/util/ArrayList;'}),
 'Lcom/oplus/findphone/client/device/DeviceListItem;':({'<init>()V'},
 {'a:Ljava/lang/String;','b:Ljava/lang/String;','d:I','f:I','h:I','i:I','j:Ljava/lang/String;'}),
 data:({'<init>()V','getDeviceId()Ljava/lang/String;','setDeviceId(Ljava/lang/String;)V','setDeviceName(Ljava/lang/String;)V',
 'setDeviceType(I)V','setDeviceStatus(I)V','setOnlineStatus(I)V','setSupportInfo(Lcom/oplus/findphone/client/device/devicedetail/SupportInfo;)V',
 'setInstructionStatusMap(Ljava/util/Map;)V'},set()),
 'Lcom/oplus/findphone/client/device/devicedetail/SupportInfo;':({'<init>()V','setRing(Z)V','setStopRing(Z)V'},set()),
 'Lcom/oplus/findphone/client/map/B;':({f'a({app}{data}{local}Z){view}'},set()),
 'Lcom/oplus/findphone/client/map/e;':({'d(Landroidx/fragment/app/Fragment;)V'},set()),
 app:({f'a(){app}'},set()),
 'Landroidx/lifecycle/MutableLiveData;':({'setValue(Ljava/lang/Object;)V'},set()),
 'Lwc/l;':(set(),{'a:Lwc/l;','g:Lwc/l;','e:Lwc/l;'}),
 'Ls3/e;':({'a(I)Ls3/e;'},set()),
 'Lcom/coloros/findmyphone/channel/api/bean/FindChannelType;':(set(),{'LOCAL_AREA_NET:Lcom/coloros/findmyphone/channel/api/bean/FindChannelType;'}),
}
evidence=[]
with zipfile.ZipFile(apk) as archive:
 for entry in ('classes.dex','classes2.dex'):
  raw=archive.read(entry)
  for cls in DEX(raw).get_classes():
   if cls.get_name() not in required:continue
   methods,fields=required.pop(cls.get_name())
   actual_methods={m.get_name()+m.get_descriptor().replace(' ','') for m in cls.get_methods()}
   actual_fields={f.get_name()+':'+f.get_descriptor() for f in cls.get_fields()}
   assert methods<=actual_methods,(cls.get_name(),methods-actual_methods)
   assert fields<=actual_fields,(cls.get_name(),fields-actual_fields)
   evidence.append(dict(class_name=cls.get_name(),methods=sorted(methods),fields=sorted(fields),dex=entry,dex_sha256=hashlib.sha256(raw).hexdigest()))
assert not required,required
base=root/'captures/device-finder-20261108/coloros17/analysis/jadx/resources/res/layout'
layouts=[base/name for name in ('item_listview.xml','item_device_support_remote_open_title.xml')]
assert '@drawable/ic_device_list_default_bg' in layouts[0].read_text(encoding='utf-8')
assert 'tv_support_remote_open_device_title' in layouts[1].read_text(encoding='utf-8')
record=dict(command='python -X utf8 scripts/verify-device-finder-ui-surface.py',apk_sha256=apk_sha,
 classes=evidence,source_sha256={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in
 [Path(__file__),root/'android/module/src/main/java/io/github/yuejie/connect/OplusFinderUiAdapter.java',*layouts]},
 scope='Original native class/field/method and layout contract only; no runtime/device/account/cloud/playback')
out.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
print(f'PASS {len(evidence)} original native UI class surfaces and 2 OEM layouts; no baseline playback/transport checks rerun')
