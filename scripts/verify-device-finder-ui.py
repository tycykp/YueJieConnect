"""Actual local UI adapter with bounded Android/OEM hook doubles; no device or cloud access."""
from pathlib import Path
import hashlib,json,subprocess
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/device-finder-repair/ui-host'
MAIN=ROOT/'android/module/src/main/java/io/github/qiangjian/connect'
JAVA=Path('C:/Program Files/Android/Android Studio/jbr/bin')
JSON=ROOT/'build/file-transfer-validation/json-src/org/json'
fixtures={
'android/os/Looper.java':'''package android.os; public class Looper {static final Looper MAIN=new Looper();public static Looper myLooper(){return MAIN;}public static Looper getMainLooper(){return MAIN;}}''',
'android/os/Bundle.java':'''package android.os; public class Bundle {}''',
'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler {public static List<Runnable> queue=new ArrayList<>(),delayed=new ArrayList<>();public Handler(Looper l){}public boolean post(Runnable r){queue.add(r);return true;}public boolean postDelayed(Runnable r,long d){delayed.add(r);return true;}public void removeCallbacks(Runnable r){queue.remove(r);delayed.remove(r);}public static void drain(){while(!queue.isEmpty())queue.remove(0).run();}public static void tick(){List<Runnable> q=new ArrayList<>(delayed);delayed.clear();for(Runnable r:q)r.run();drain();}}''',
'android/util/Log.java':'''package android.util;public class Log {public static int w(String t,String m){return 0;}}''',
'android/content/res/Resources.java':'''package android.content.res;public class Resources {public int getIdentifier(String n,String t,String p){return 7;}}''',
'android/content/Context.java':'''package android.content;public class Context {public android.content.res.Resources getResources(){return new android.content.res.Resources();}}''',
'android/view/View.java':'''package android.view;public class View {public static final int GONE=8;public int visibility;public OnClickListener click;public interface OnClickListener{void onClick(View v);}public void setOnClickListener(OnClickListener l){click=l;}public void setVisibility(int v){visibility=v;}}''',
'android/view/ViewGroup.java':'''package android.view;public class ViewGroup extends View {}''',
'android/view/LayoutInflater.java':'''package android.view;public class LayoutInflater {}''',
'android/widget/TextView.java':'''package android.widget;public class TextView extends android.view.View {public CharSequence text;public void setText(CharSequence t){text=t;}}''',
'android/widget/ImageView.java':'''package android.widget;public class ImageView extends android.view.View {public int resource;public void setImageResource(int r){resource=r;}}''',
'io/github/libxposed/api/XposedInterface.java':'''package io.github.libxposed.api;public interface XposedInterface {interface HookHandle{void unhook();}interface Chain{Object getThisObject();Object getArg(int n);Object proceed()throws Throwable;Object proceed(Object[] args)throws Throwable;}}''',
'io/github/libxposed/api/XposedModule.java':'''package io.github.libxposed.api;import java.lang.reflect.*;import java.util.*;public class XposedModule {
 public interface Interceptor{Object intercept(XposedInterface.Chain c)throws Throwable;}public interface Body{Object run(Object[] a)throws Throwable;}
 public static final Map<Executable,Interceptor> hooks=new HashMap<>();public Builder hook(Executable e){return new Builder(e);}
 public static class Builder{final Executable e;Builder(Executable e){this.e=e;}public Builder setId(String id){return this;}public XposedInterface.HookHandle intercept(Interceptor i){hooks.put(e,i);return ()->hooks.remove(e);}}
 public static Object call(Class<?> owner,String name,Class<?>[] types,Object receiver,Object[] args,Body body){try{Method m=owner.getMethod(name,types);Interceptor i=hooks.get(m);if(i==null)return body.run(args);return i.intercept(new XposedInterface.Chain(){public Object getThisObject(){return receiver;}public Object getArg(int n){return args[n];}public Object proceed()throws Throwable{return body.run(args);}public Object proceed(Object[] a)throws Throwable{return body.run(a);}});}catch(Throwable e){throw new RuntimeException(e);}}
}''',
'androidx/fragment/app/Fragment.java':'''package androidx.fragment.app;public class Fragment {public int backs;}''',
'androidx/recyclerview/widget/RecyclerView.java':'''package androidx.recyclerview.widget;public class RecyclerView {public static class C {}}''',
'androidx/recyclerview/widget/z.java':'''package androidx.recyclerview.widget;import java.util.*;import io.github.libxposed.api.XposedModule;
 public class z {public List<Object> received=new ArrayList<>();public Object c(int i){return received.get(i);}public void d(List<?> list){XposedModule.call(z.class,"d",new Class[]{List.class},this,new Object[]{list},a->{received=new ArrayList<>((List<?>)a[0]);return null;});}}''',
'androidx/lifecycle/MutableLiveData.java':'''package androidx.lifecycle;public class MutableLiveData {public Object value;public void setValue(Object o){value=o;}}''',
'com/oplus/FindMyPhoneApplication.java':'''package com.oplus;public class FindMyPhoneApplication extends android.content.Context {static final FindMyPhoneApplication APP=new FindMyPhoneApplication();public static FindMyPhoneApplication a(){return APP;}}''',
'hc/i.java':'''package hc;public class i {public static class b {public com.oplus.findphone.client.device.DeviceListItem a;public boolean b;public int c;}public static class e {public int a;}}''',
'wc/l.java':'''package wc;public enum l {a,b,c,d,e,f,g,h}''',
's3/e.java':'''package s3;public enum e {LOCATION,RING,STOP_RING,ERASE_DATA;public static e a(int code){return code==15?RING:LOCATION;}}''',
'wc/e.java':'''package wc;import io.github.libxposed.api.XposedModule;import androidx.recyclerview.widget.RecyclerView;import android.widget.*;
 public class e extends androidx.recyclerview.widget.z {public int originalBinds;public static class a extends RecyclerView.C {public ImageView a=new ImageView();}public static class c extends RecyclerView.C {public TextView a=new TextView(),b=new TextView();}
 public void onBindViewHolder(RecyclerView.C holder,int position){XposedModule.call(e.class,"onBindViewHolder",new Class[]{RecyclerView.C.class,int.class},this,new Object[]{holder,position},args->{originalBinds++;if(holder instanceof a)((a)holder).a.resource=999;return null;});}}''',
'com/oplus/findphone/client/device/DeviceListItem.java':'''package com.oplus.findphone.client.device;public class DeviceListItem {public String a,b,j;public int d,f,h,i;public boolean n;}''',
'com/oplus/findphone/client/device/devicedetail/SupportInfo.java':'''package com.oplus.findphone.client.device.devicedetail;public class SupportInfo {public boolean ring,stopRing;public void setRing(boolean b){ring=b;}public void setStopRing(boolean b){stopRing=b;}}''',
'com/oplus/findphone/client/device/devicedetail/DeviceDetailData.java':'''package com.oplus.findphone.client.device.devicedetail;import java.util.*;public class DeviceDetailData {
 public String id,name;public int type,status,online;public SupportInfo support;public Map<?,Integer> instructions;
 public String getDeviceId(){return id;}public void setDeviceId(String v){id=v;}public void setDeviceName(String v){name=v;}public void setDeviceType(int v){type=v;}public void setDeviceStatus(int v){status=v;}public void setOnlineStatus(int v){online=v;}public void setSupportInfo(SupportInfo v){support=v;}public void setInstructionStatusMap(Map<?,Integer> v){instructions=v;}}''',
'com/oplus/findphone/client/device/devicedetail/a.java':'''package com.oplus.findphone.client.device.devicedetail;public class a {public int b=-1;}''',
'com/oplus/findphone/client/device/devicedetail/b.java':'''package com.oplus.findphone.client.device.devicedetail;import java.util.*;public class b {public DeviceDetailData a;public ArrayList<String> g=new ArrayList<>(),h=new ArrayList<>(),i=new ArrayList<>(),j=new ArrayList<>();}''',
'com/oplus/findphone/client/map/z.java':'''package com.oplus.findphone.client.map;public class z {public com.oplus.findphone.client.device.devicedetail.a a=new com.oplus.findphone.client.device.devicedetail.a();}''',
'com/oplus/findphone/client/map/B.java':'''package com.oplus.findphone.client.map;import io.github.libxposed.api.XposedModule;import com.oplus.*;import com.oplus.findphone.client.device.devicedetail.*;
 public class B {public static b a(FindMyPhoneApplication app,DeviceDetailData data,a local,boolean cloud){return (b)XposedModule.call(B.class,"a",new Class[]{FindMyPhoneApplication.class,DeviceDetailData.class,a.class,boolean.class},null,new Object[]{app,data,local,cloud},args->{b value=new b();value.a=data;value.g.add("native-ring");value.h.add("native-cloud-history");value.i.add("native-product");value.j.add("native-account-removal");return value;});}}''',
'com/oplus/findphone/client/map/e.java':'''package com.oplus.findphone.client.map;public class e {public static void d(androidx.fragment.app.Fragment f){f.backs++;}}''',
'com/oplus/findphone/client/ViewModel/DeviceDetailViewModel.java':'''package com.oplus.findphone.client.ViewModel;import androidx.lifecycle.MutableLiveData;import io.github.libxposed.api.XposedModule;
 public class DeviceDetailViewModel {public MutableLiveData a=new MutableLiveData(),b=new MutableLiveData(),c=new MutableLiveData();public com.oplus.findphone.client.map.z d=new com.oplus.findphone.client.map.z();public String h;public int i,cloudFetches;
 public void e(){XposedModule.call(getClass(),"e",new Class[]{},this,new Object[]{},args->{cloudFetches++;return null;});}
 public void onCleared(){XposedModule.call(getClass(),"onCleared",new Class[]{},this,new Object[]{},args->null);}}''',
'com/oplus/findphone/client/map/t.java':'''package com.oplus.findphone.client.map;import io.github.libxposed.api.XposedModule;import android.view.*;import android.os.Bundle;
 public class t extends androidx.fragment.app.Fragment {public com.oplus.findphone.client.ViewModel.DeviceDetailViewModel m=new com.oplus.findphone.client.ViewModel.DeviceDetailViewModel();
 public void onViewCreated(View v,Bundle b){XposedModule.call(t.class,"onViewCreated",new Class[]{View.class,Bundle.class},this,new Object[]{v,b},args->{m.e();return null;});}
 public void onDestroyView(){XposedModule.call(t.class,"onDestroyView",new Class[]{},this,new Object[]{},args->null);}}''',
'com/oplus/findphone/client/map/D.java':'''package com.oplus.findphone.client.map;import io.github.libxposed.api.XposedModule;import android.view.*;import android.os.Bundle;import com.oplus.findphone.client.device.DeviceListItem;
 public class D extends androidx.fragment.app.Fragment {public wc.e c=new wc.e();public DeviceListItem selected;public t detail;
 public View onCreateView(LayoutInflater i,ViewGroup g,Bundle b){return (View)XposedModule.call(D.class,"onCreateView",new Class[]{LayoutInflater.class,ViewGroup.class,Bundle.class},this,new Object[]{i,g,b},args->new View());}
 public void n(DeviceListItem item){selected=item;detail=new t();detail.m.h=item.a;detail.m.i=item.d;}}''',
'com/oplus/findphone/client/map/N.java':'''package com.oplus.findphone.client.map;import io.github.libxposed.api.XposedModule;import android.view.View;public class N {public int a=4;public Object b,c;public int cloudClicks;public N(Object adapter,Object item){b=adapter;c=item;}
 public void onClick(View v){XposedModule.call(N.class,"onClick",new Class[]{View.class},this,new Object[]{v},args->{cloudClicks++;return null;});}}''',
'io/github/qiangjian/connect/BridgeClient.java':'''package io.github.qiangjian.connect;import java.util.*;import java.util.function.*;import org.json.*;
 public class BridgeClient {public interface Subscription{void close();}public JSONObject selected;public List<Consumer<JSONObject>> listeners=new ArrayList<>();public List<Call> calls=new ArrayList<>();public List<String> cancels=new ArrayList<>();
 public record Call(String id,String session,String op,String request,JSONObject payload,Consumer<JSONObject> callback){}public android.content.Context context(){return com.oplus.FindMyPhoneApplication.a();}public JSONObject projection(){return selected;}
 public Subscription addProjectionListener(Consumer<JSONObject> c){listeners.add(c);return ()->listeners.remove(c);}public void update(JSONObject value){selected=value;for(var c:List.copyOf(listeners))c.accept(value);}
 public void request(String id,String session,String op,String request,JSONObject payload,Consumer<JSONObject> callback){calls.add(new Call(id,session,op,request,payload,callback));}public void cancel(String s,String r,Consumer<JSONObject> callback){cancels.add(r);}}''',
}
generated=[]
for name,content in fixtures.items():
 p=OUT/'sources'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8');generated.append(p)
test=ROOT/'scripts/device-finder-ui/OplusFinderUiCheck.java'
source=MAIN/'OplusFinderUiAdapter.java'
inputs=[source,MAIN/'OplusFinderSender.java',test,*generated,*sorted(JSON.glob('*.java'))]
commands=[
 [JAVA/'javac.exe','--release','17','-encoding','UTF-8','-d',OUT/'classes',*inputs],
 [JAVA/'java.exe','-cp',OUT/'classes','io.github.qiangjian.connect.OplusFinderUiCheck'],
]
results=[]
for command in commands:
 p=subprocess.run([str(v) for v in command],capture_output=True,text=True,errors='replace',timeout=40)
 results.append(dict(command=[str(v) for v in command],exit_code=p.returncode,output=p.stdout+p.stderr));print(results[-1]['output'].strip())
 if p.returncode:break
record=dict(commands=results,source_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*inputs,Path(__file__)]},
 scope='Actual OplusFinderUiAdapter with hook/Android/native display and transport doubles; no native account, device, cloud, APK build/install, or audible playback. Original surface independently pinned by ui-original-dex.json.')
(OUT/'verification.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
raise SystemExit(results[-1]['exit_code'])
