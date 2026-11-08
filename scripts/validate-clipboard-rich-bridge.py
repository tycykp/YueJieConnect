"""Exercises actual ClipboardBridge rich registration, request, receipt and lifecycle glue.
Plain endpoints and Binder are doubles; no device or native network success is inferred.
"""
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('rich_tests', ROOT / 'scripts/validate-clipboard-rich.py')
rich = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rich)
OUT = ROOT / 'build/clipboard-validation/rich-bridge-host'
rich.OUT = OUT
PKG = rich.PKG
stubs = dict(rich.stubs)
stubs['android/app/Application.java'] = '''package android.app;public class Application {public static String getProcessName(){return "com.oplus.linker:app";}}'''
stubs['android/util/Log.java'] = '''package android.util;public class Log {public static final int INFO=4,WARN=5;}'''
stubs['io/github/libxposed/api/XposedModule.java'] = stubs['io/github/libxposed/api/XposedModule.java'].replace('public Builder hook(', 'public void log(int p,String t,String m){} public Builder hook(')
stubs[PKG+'BridgeClient.java'] = r'''package io.github.qiangjian.connect;
import android.content.Context;import org.json.*;import java.util.*;import java.util.function.Consumer;
class BridgeClient {
 interface Subscription{void close();} interface TaskCompletion {void success(JSONObject d);void fail(String c);}
 interface VendorExecutor {void execute(JSONObject t,TaskCompletion c);void cancel(String s,String r);void sessionChanged();}
 final Context context;JSONObject projection;Map<String,VendorExecutor> executors=new HashMap<>();
 String peer,session,operation,request;JSONObject payload;Consumer<JSONObject> response;int requests,cancels;Consumer<JSONObject> listener;
 BridgeClient(Context c,JSONObject p){context=c;projection=p;}Context context(){return context;}JSONObject projection(){return projection;}
 Subscription addProjectionListener(Consumer<JSONObject> c){listener=c;return ()->listener=null;}
 Subscription registerExecutor(String op,VendorExecutor e){executors.put(op,e);return ()->executors.remove(op);}
 void request(String p,String s,String op,String r,JSONObject body,Consumer<JSONObject> callback){requests++;peer=p;session=s;operation=op;request=r;payload=body;response=callback;}
 void cancel(String s,String r,Consumer<JSONObject> callback){cancels++;callback.accept(new JSONObject());}
 void changed(){listener.accept(projection);}
}'''
stubs[PKG+'OplusClipboardAdapter.java'] = r'''package io.github.qiangjian.connect;
import org.json.JSONObject;import java.util.concurrent.*;import java.util.function.*;
class OplusClipboardAdapter {
 record Receipt(String id,String session,String request,int utf8Bytes){}
 interface Transport {boolean ready(JSONObject p);CompletableFuture<Receipt> write(String id,String s,String r,String t);CompletableFuture<Boolean> cancel(String id,String s,String r);}
 OplusClipboardAdapter(io.github.libxposed.api.XposedModule m,ClassLoader l,BooleanSupplier a,Supplier<JSONObject> p,Transport t)throws ReflectiveOperationException{}
 void install()throws ReflectiveOperationException{}void updateSource(android.content.Context c,boolean a){}void sessionChanged(){}void close(){}CompletableFuture<Receipt> lastTransfer(){return null;}
}'''
stubs[PKG+'XiaomiClipboardAdapter.java'] = stubs[PKG+'OplusClipboardAdapter.java'].replace('class OplusClipboardAdapter','class XiaomiClipboardAdapter').replace(' OplusClipboardAdapter(', ' XiaomiClipboardAdapter(').replace('Transport t)', 'OplusClipboardAdapter.Transport t)').replace('CompletableFuture<Receipt> lastTransfer()', 'CompletableFuture<OplusClipboardAdapter.Receipt> lastTransfer()')
for name in ['OplusClipboardReceiver', 'XiaomiClipboardReceiver']:
    stubs[PKG+name+'.java'] = '''package io.github.qiangjian.connect;
import java.util.concurrent.*;import java.util.function.*;import org.json.JSONObject;
class NAME {record Result(String session,String request,int utf8Bytes,String error){}
NAME(android.content.Context c,ClassLoader l,BooleanSupplier a,Supplier<JSONObject> p)throws ReflectiveOperationException{}
CompletableFuture<Result> execute(String s,String r,String t,long d){return null;}CompletableFuture<Boolean> cancel(String s,String r){return CompletableFuture.completedFuture(false);}void sessionChanged(){}void close(){}}
'''.replace('NAME', name)
stubs[PKG+'OplusClipboardEndpoint.java'] = '''package io.github.qiangjian.connect;class OplusClipboardEndpoint {OplusClipboardEndpoint(ClassLoader l)throws ReflectiveOperationException{}boolean enabled(android.content.Context c){return true;}}'''
stubs[PKG+'XiaomiClipboardEndpoint.java'] = '''package io.github.qiangjian.connect;class XiaomiClipboardEndpoint {XiaomiClipboardEndpoint(ClassLoader l)throws ReflectiveOperationException{}boolean available(){return true;}}'''
stubs[PKG+'XiaomiClipboardSourceLease.java'] = '''package io.github.qiangjian.connect;class XiaomiClipboardSourceLease {XiaomiClipboardSourceLease(ClassLoader l)throws ReflectiveOperationException{}void update(boolean b)throws ReflectiveOperationException{}void close(){}}'''
harness = r'''package io.github.qiangjian.connect;
import android.content.*;import android.os.*;import io.github.libxposed.api.XposedModule;
import org.json.*;import java.util.*;import java.util.concurrent.*;
public class ClipboardRichBridgeHarness {
 static int checks;static boolean allowed=true;static String A="a".repeat(64),B="b".repeat(64);
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
 static JSONObject profile(boolean rich)throws Exception {JSONArray ops=new JSONArray().put("clipboard_write");if(rich)ops.put("clipboard_rich_write");
 return new JSONObject().put("id","peer").put("session_id",A).put("operations",ops);}
 static ClipboardRichContent.Html html(String s){return new ClipboardRichContent.Html(s,"<b>"+s+"</b>");}
 static JSONObject receipt(BridgeClient c,int bytes,String mime)throws Exception{return new JSONObject().put("status","ok").put("session_id",c.session).put("request_id",c.request)
 .put("result",new JSONObject().put("operation",c.operation).put("data",new JSONObject().put("mime",mime).put("applied",true).put("utf8_bytes",bytes)));}
 static void failed(CompletableFuture<?> f){check(f.isCompletedExceptionally(),"invalid/missing/stale receipt is not applied");}
 static class Done implements BridgeClient.TaskCompletion{JSONObject data;String error;public void success(JSONObject d){data=d;}public void fail(String e){error=e;}}
 static JSONObject task(BridgeClient c,String id,String session,String request,ClipboardRichContent.Html h)throws Exception{return new JSONObject().put("projection_id",id).put("session_id",session).put("request_id",request).put("deadline_elapsed_ms",2000).put("payload",ClipboardRichCodec.encode(h));}
 public static void main(String[] args)throws Exception {
  Context ctx=new Context("com.oplus.linker");BridgeClient client=new BridgeClient(ctx,profile(false));
  ClipboardBridge bridge=new ClipboardBridge(new XposedModule(),client,ClipboardRichBridgeHarness.class.getClassLoader(),()->allowed,2);bridge.install(ctx);Handler.drain();
  check(client.executors.keySet().equals(Set.of("clipboard_write","clipboard_rich_write")),"only actual installed native endpoints register rich operation");
  check(ctx.cm.reads==0&&ctx.cm.listeners.isEmpty(),"no rich authorization means no source/history read");ctx.cm.setPrimaryClip(html("before").localClip());check(client.requests==0,"plain capability does not send HTML");
  client.projection=profile(true);client.changed();Handler.drain();check(ctx.cm.listeners.size()==1&&client.requests==0,"new rich capability starts genuine copies only");
  var value=html("synthetic-one");ctx.cm.setPrimaryClip(value.localClip());check(client.requests==1&&client.operation.equals("clipboard_rich_write"),"HTML uses dedicated actual bridge operation");
  check(ClipboardRichCodec.decode(client.payload).equals(value)&&client.peer.equals("peer")&&client.session.equals(A),"wire exact pair and session snapshot");
  check(!bridge.lastRichTransfer().isDone(),"source not acknowledged before remote response");
  ctx.cm.setPrimaryClip(html("busy").localClip());check(client.requests==1,"single-flight rich capacity does not fabricate a second receipt");
  client.response.accept(receipt(client,value.utf8Bytes(),"text/plain"));failed(bridge.lastRichTransfer());check(client.cancels==1,"bad receipt sends actual best-effort cancellation");
  value=html("synthetic-two");ctx.cm.setPrimaryClip(value.localClip());check(client.requests==2,"settled source releases capacity");client.response.accept(receipt(client,value.utf8Bytes(),"text/html"));
  check(bridge.lastRichTransfer().join().utf8Bytes()==value.utf8Bytes(),"receipt binds combined plain plus HTML bytes");
  value=html("synthetic-three");ctx.cm.setPrimaryClip(value.localClip());var staleResponse=client.response;var pending=bridge.lastRichTransfer();client.projection.put("session_id",B);client.changed();Handler.drain();failed(pending);
  staleResponse.accept(receipt(client,value.utf8Bytes(),"text/html"));failed(pending);check(client.cancels==2,"session invalidation propagates cancel once");
  client.projection=profile(true);client.changed();Handler.drain();
  var executor=client.executors.get("clipboard_rich_write");var done=new Done();int requests=client.requests;executor.execute(task(client,"peer",A,"incoming",html("received")),done);Handler.drain();
  check(done.error==null&&done.data.getBoolean("applied")&&done.data.getString("mime").equals("text/html"),"native import supplies strict successful completion");
  check(done.data.getInt("utf8_bytes")==html("received").utf8Bytes()&&client.requests==requests,"received HTML is read back without echoing into outgoing RPC");
  var wrong=new Done();executor.execute(task(client,"other",A,"wrong",html("wrong")),wrong);Handler.drain();check(wrong.error.equals("stale_session"),"task must bind true projection peer");
  var malformed=new Done();JSONObject bad=task(client,"peer",A,"bad",html("bad"));bad.getJSONObject("payload").put("uri","content://synthetic/item");executor.execute(bad,malformed);check(malformed.error.equals("invalid_payload"),"mixed fields cannot sneak local URI through HTML");
  var queued=new Done();executor.execute(task(client,"peer",A,"queued",html("queued")),queued);Handler.one();executor.cancel(A,"queued");var apply=Handler.q.remove();Handler.one();Handler.q.add(apply);Handler.drain();check(queued.error.equals("cancelled"),"public cancellation reaches rich queue");
  value=html("close-pending");ctx.cm.setPrimaryClip(value.localClip());pending=bridge.lastRichTransfer();bridge.close();Handler.drain();failed(pending);
  check(client.executors.isEmpty()&&ctx.cm.listeners.isEmpty(),"close releases both executor leases and genuine rich listener");
  System.out.println("PASS "+checks+" ClipboardBridge rich glue assertions; Binder/OEM doubles, no device proof");
 }
}'''


def main():
    sources = OUT / 'sources'
    classes = OUT / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    stubs[PKG+'ClipboardRichBridgeHarness.java'] = harness
    for relative, body in stubs.items():
        path = sources / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
    actual = [rich.MAIN / name for name in ['ClipboardBridge.java', 'ClipboardRichContent.java', 'ClipboardNativeContent.java',
        'ClipboardNativeClock.java', 'ClipboardEchoGuard.java', 'ClipboardRichCodec.java', 'ClipboardRichReceiver.java',
        'ClipboardRichSource.java', 'OplusClipboardTasks.java', 'OplusClipboardCodec.java', 'ClipboardWireCodec.java']]
    json_sources = sorted((ROOT / 'build/file-transfer-validation/json-src/org/json').glob('*.java'))
    rich.run('compile', rich.JDK / 'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-d', classes,
        *actual, *json_sources, *sorted(sources.rglob('*.java')))
    rich.run('rich-bridge-tests', rich.JDK / 'java.exe', '-cp', classes, 'io.github.qiangjian.connect.ClipboardRichBridgeHarness')
    (OUT / 'evidence.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-rich-bridge.py',
        source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [*actual, Path(__file__), ROOT / 'scripts/validate-clipboard-rich.py']},
        generated_test_sha256={str(p.relative_to(OUT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources.rglob('*.java')},
        result='passed', apk=None, device_operations=False, scope='real ClipboardBridge rich glue, OEM/plain/Binder doubles'), indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
