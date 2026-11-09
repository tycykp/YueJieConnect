"""Actual binding's authorization withdrawal and late completion; endpoint/Android are doubles."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/device-finder-repair/binding'
MAIN=ROOT/'android/module/src/main/java/io/github/yuejie/connect'
JAVA=Path('C:/Program Files/Android/Android Studio/jbr/bin')
ANDROID=Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/platforms/android-37.0/android.jar'
API=ROOT/'build/device-finder-validation/libxposed-api.jar'
JSON=ROOT/'build/file-transfer-validation/json-src/org/json'
sources={
 'android/os/Looper.java':'''package android.os; public final class Looper {private static final Looper MAIN=new Looper();
 public static Looper myLooper(){return MAIN;} public static Looper getMainLooper(){return MAIN;}}''',
 'android/os/SystemClock.java':'''package android.os; public final class SystemClock {public static long elapsedRealtime(){return 1;}}''',
 'android/content/Context.java':'''package android.content; public abstract class Context {}''',
 'io/github/libxposed/api/XposedModule.java':'''package io.github.libxposed.api; public abstract class XposedModule {}''',
 'android/util/Log.java':'''package android.util; public final class Log {public static int w(String tag,String message){return 0;}}''',
 'io/github/yuejie/connect/OplusFinderChannelAdapter.java':'''package io.github.yuejie.connect;
 final class OplusFinderChannelAdapter {static OplusFinderChannelAdapter last; static boolean failCreate,failClose;
 boolean closed; final java.util.function.BooleanSupplier allowed;
 OplusFinderChannelAdapter(io.github.libxposed.api.XposedModule module,BridgeClient client,
 ClassLoader loader,java.util.function.BooleanSupplier allowed){if(failCreate)throw new IllegalStateException();this.allowed=allowed;last=this;}
 void close(){closed=true;if(failClose)throw new IllegalStateException();}}''',
 'io/github/yuejie/connect/BridgeClient.java':'''package io.github.yuejie.connect;
 import org.json.JSONObject; import java.util.function.Consumer;
 final class BridgeClient {
 interface Subscription {void close();} interface TaskCompletion {void success(JSONObject data);void fail(String code);}
 interface VendorExecutor {void execute(JSONObject task,TaskCompletion completion);void cancel(String session,String request);void sessionChanged();}
 JSONObject selected; Consumer<JSONObject> listener; VendorExecutor executor; int registrations;
 android.content.Context context(){return new android.content.Context(){};} JSONObject projection(){return selected;}
 Subscription registerExecutor(String name,VendorExecutor value){executor=value; registrations++; return ()->registrations--;}
 Subscription addProjectionListener(Consumer<JSONObject> value){listener=value; return ()->listener=null;}
 void update(JSONObject value){selected=value;if(listener!=null)listener.accept(value);}
 }''',
 'io/github/yuejie/connect/DeviceFinderEndpoint.java':'''package io.github.yuejie.connect;
 import org.json.JSONObject; import java.util.function.Consumer;
 final class DeviceFinderEndpoint implements AutoCloseable {
 static DeviceFinderEndpoint last; String session; int executions,cancels; Consumer<JSONObject> pending; boolean closed;
 DeviceFinderEndpoint(android.content.Context context,ClassLoader loader,int source,io.github.libxposed.api.XposedModule module){last=this;}
 void bind(String value,String peer){session=value;}
 void execute(String session,String request,long deadline,JSONObject payload,Consumer<JSONObject> completion){executions++;pending=completion;}
 void cancel(){cancels++;} void cancel(String session,String request){cancels++;} public void close(){closed=true;}
 }''',
 'io/github/yuejie/connect/DeviceFinderBindingCheck.java':'''package io.github.yuejie.connect;
 import org.json.*;
 public final class DeviceFinderBindingCheck {
 static int count; static void check(boolean ok,String why){count++;if(!ok)throw new AssertionError(why);}
 static JSONObject peer(String id,boolean enabled)throws Exception{return new JSONObject().put("id",id).put("session_id","session")
 .put("operations",enabled?new JSONArray().put("device_finder"):new JSONArray());}
 static JSONObject task(String id)throws Exception{return new JSONObject().put("projection_id",id).put("session_id","session")
 .put("request_id","request").put("deadline_elapsed_ms",100).put("payload",new JSONObject().put("action","start"));}
 static class Completion implements BridgeClient.TaskCompletion {int successes,errors; String code;
 public void success(JSONObject data){successes++;} public void fail(String value){errors++;code=value;}}
 public static void main(String[] args)throws Exception{
 BridgeClient client=new BridgeClient(); client.selected=peer("old",true);
 DeviceFinderBinding binding=new DeviceFinderBinding(client,DeviceFinderBindingCheck.class.getClassLoader(),1);
 DeviceFinderEndpoint endpoint=DeviceFinderEndpoint.last;
 check(client.registrations==1 && "session".equals(endpoint.session),"effective finder binds");
 client.update(peer("old",false)); check(endpoint.session==null,"grant withdrawal unbinds same session");
 Completion denied=new Completion(); binding.execute(task("old"),denied);
 check("permission_denied".equals(denied.code) && endpoint.executions==0,"queued task cannot bypass current grant");
 client.update(peer("old",true)); check("session".equals(endpoint.session),"grant restores binding");
 Completion pending=new Completion();binding.execute(task("old"),pending);
 check(endpoint.executions==1 && pending.successes==0,"execute awaits endpoint");
 client.update(peer("new",true));endpoint.pending.accept(new JSONObject().put("status","ok").put("ringing",true));
 check(pending.successes==0 && pending.errors==1 && endpoint.cancels==1,"late same-session different-peer completion cancelled");
 Completion revoked=new Completion();binding.execute(task("new"),revoked); client.update(peer("new",false));
 endpoint.pending.accept(new JSONObject().put("status","ok").put("ringing",true));
 check(revoked.successes==0 && endpoint.cancels==2,"late success after grant revoked cancelled");
 client.update(peer("new",true)); Completion good=new Completion();binding.execute(task("new"),good);
 endpoint.pending.accept(new JSONObject().put("status","ok").put("ringing",true));
 check(good.successes==1 && good.errors==0,"live effective completion forwarded");
 client.update(null);check(endpoint.session==null,"disconnect unbinds");
 binding.close();check(endpoint.closed && client.registrations==0 && client.listener==null,"close withdraws listener executor endpoint");
 BridgeClient oplus=new BridgeClient();oplus.selected=peer("oplus",true);
 DeviceFinderBinding both=new DeviceFinderBinding(oplus,DeviceFinderBindingCheck.class.getClassLoader(),2);
 OplusFinderChannelAdapter nativeSender=OplusFinderChannelAdapter.last;DeviceFinderEndpoint receiver=DeviceFinderEndpoint.last;
 check(nativeSender!=null && nativeSender.allowed.getAsBoolean(),"source2 automatically installs native channel sender");
 both.close();check(nativeSender.closed && !nativeSender.allowed.getAsBoolean() && receiver.closed,"source2 closes sender and receiver");
 OplusFinderChannelAdapter.failCreate=true;
 DeviceFinderBinding receivingOnly=new DeviceFinderBinding(oplus,DeviceFinderBindingCheck.class.getClassLoader(),2);
 check(oplus.registrations==1,"sender signature failure preserves verified receiving executor");receivingOnly.close();
 OplusFinderChannelAdapter.failCreate=false;OplusFinderChannelAdapter.failClose=true;
 DeviceFinderBinding closeFailure=new DeviceFinderBinding(oplus,DeviceFinderBindingCheck.class.getClassLoader(),2);
 receiver=DeviceFinderEndpoint.last;boolean failed=false;try{closeFailure.close();}catch(IllegalStateException expected){failed=true;}
 check(failed && receiver.closed && oplus.registrations==0,"sender cleanup exception still closes endpoint");
 System.out.println("PASS "+count+" actual finder binding lifecycle assertions; endpoint double only");
 }}'''
}
generated=[]
for name,content in sources.items():
 path=OUT/'sources'/name
 path.parent.mkdir(parents=True,exist_ok=True)
 path.write_text(content,encoding='utf-8');generated.append(path)
production=MAIN/'DeviceFinderBinding.java'
commands=[
 [JAVA/'javac.exe','--release','17','-encoding','UTF-8','-d',OUT/'jvm',production,*generated,*sorted(JSON.glob('*.java'))],
 [JAVA/'java.exe','-cp',OUT/'jvm','io.github.yuejie.connect.DeviceFinderBindingCheck'],
 [JAVA/'javac.exe','--release','17','-encoding','UTF-8','-cp',str(ANDROID)+os.pathsep+str(API),'-d',OUT/'android',
  production,*[MAIN/name for name in ('DeviceFinderController.java','DeviceFinderEndpoint.java','XiaomiFinderEndpoint.java',
                                     'OplusFinderNativeEndpoint.java','OplusFinderSender.java','OplusFinderChannelAdapter.java','OplusFinderUiAdapter.java',
                                     'BridgeClient.java','IBridge.java','NativeLogicalSessions.java')]],
]
results=[]
if '--compile-only' in sys.argv:
 previous=json.loads((OUT/'verification.json').read_text(encoding='utf-8'))
 checked={Path(k).as_posix():v for k,v in previous['source_sha256'].items()}
 for path in [production,*generated,*sorted(JSON.glob('*.java'))]:
  assert checked[str(path.relative_to(ROOT).as_posix())]==hashlib.sha256(path.read_bytes()).hexdigest(),path
 results=[dict(item,reused=True) for item in previous['commands'][:2]]
 assert all(item['exit_code']==0 for item in results)
 print('REUSED unchanged actual Binding and host fixtures: 14 lifecycle assertions')
for command in commands[2:] if '--compile-only' in sys.argv else commands:
 process=subprocess.run([str(v) for v in command],capture_output=True,text=True,errors='replace',timeout=40)
 results.append(dict(command=[str(v) for v in command],exit_code=process.returncode,output=process.stdout+process.stderr))
 print(results[-1]['output'].strip())
 if process.returncode:break
inputs=[production,*generated,Path(__file__),*sorted(JSON.glob('*.java'))]
inputs.extend(MAIN/name for name in ('DeviceFinderController.java','DeviceFinderEndpoint.java','XiaomiFinderEndpoint.java',
                                   'OplusFinderNativeEndpoint.java','OplusFinderSender.java','OplusFinderChannelAdapter.java','OplusFinderUiAdapter.java',
                                   'BridgeClient.java','IBridge.java','NativeLogicalSessions.java'))
record=dict(commands=results,source_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},
            dependencies_sha256={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in (ANDROID,API)},
            scope='Binding permission/late-callback lifecycle with endpoint double plus real Android API compilation; no old module baseline reruns or OEM/device/playback')
(OUT/'verification.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
raise SystemExit(results[-1]['exit_code'])
