"""Targeted rich clipboard conversion/echo/lifecycle checks. OEM, platform and hooks are doubles.
Uses the actual Android org.json sources already prepared by the files validation, not an APK.
"""
import hashlib
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/clipboard-validation/rich-host'
MAIN = ROOT / 'android/module/src/main/java/io/github/yuejie/connect'
JDK = Path(os.environ.get('CLIPBOARD_JDK', r'C:\Program Files\Android\Android Studio\jbr')) / 'bin'
PKG = 'io/github/yuejie/connect/'
stubs = {
'android/os/Looper.java': '''package android.os; public class Looper {static final Looper main=new Looper();static final Thread mainThread=Thread.currentThread();
public static boolean worker;public static Looper getMainLooper(){return main;}public static Looper myLooper(){return !worker&&Thread.currentThread()==mainThread?main:null;}}''',
'android/os/SystemClock.java': '''package android.os;public class SystemClock {public static long now=1000;
public static long elapsedRealtime(){return now;}public static long uptimeMillis(){return now;}}''',
'android/os/Handler.java': '''package android.os;public class Handler {public static final java.util.concurrent.ConcurrentLinkedDeque<Runnable> q=new java.util.concurrent.ConcurrentLinkedDeque<>();
public static final java.util.concurrent.CopyOnWriteArrayList<Runnable> delayed=new java.util.concurrent.CopyOnWriteArrayList<>();
public Handler(Looper l){}public boolean post(Runnable r){q.add(r);return true;}public boolean postDelayed(Runnable r,long ms){delayed.add(r);return true;}
public void removeCallbacks(Runnable r){delayed.removeIf(v->v==r);}public static void one(){q.remove().run();}public static void drain(){Runnable r;while((r=q.poll())!=null)r.run();}}
''',
'android/net/Uri.java': '''package android.net;public record Uri(String value){public static Uri parse(String v){return new Uri(v);}
public String getScheme(){return value.split(":")[0];}public String getAuthority(){return value.contains("://")?value.substring(value.indexOf("://")+3).split("/")[0]:null;}}''',
'android/content/Intent.java': 'package android.content;public class Intent {}',
'android/content/ClipDescription.java': '''package android.content;public class ClipDescription {public String[] types;public long time;
public ClipDescription(CharSequence label,String[] t){types=t;}public boolean hasMimeType(String s){return java.util.Arrays.asList(types).contains(s);}
public long getTimestamp(){return time;}}''',
'android/content/ClipData.java': '''package android.content;public class ClipData {
public static class Item {final CharSequence text;final String html;final Intent intent;final android.net.Uri uri;
public Item(android.net.Uri u){this(null,null,null,u);}public Item(CharSequence t,String h,Intent i,android.net.Uri u){text=t;html=h;intent=i;uri=u;}
public CharSequence getText(){return text;}public String getHtmlText(){return html;}public Intent getIntent(){return intent;}public android.net.Uri getUri(){return uri;}}
final ClipDescription d;final java.util.List<Item> items=new java.util.ArrayList<>();
public ClipData(ClipDescription d,Item i){this.d=d;items.add(i);}public ClipData(CharSequence label,String[] types,Item i){this(new ClipDescription(label,types),i);}
public ClipDescription getDescription(){return d;}public int getItemCount(){return items.size();}public Item getItemAt(int n){return items.get(n);}public void addItem(Item i){items.add(i);}}
''',
'android/content/ClipboardManager.java': '''package android.content;public class ClipboardManager {
public interface OnPrimaryClipChangedListener{void onPrimaryClipChanged();}
public final java.util.List<OnPrimaryClipChangedListener> listeners=new java.util.ArrayList<>();public ClipData clip;public int reads,writes;public long time=100;
public boolean deny,drop;public Runnable afterWrite;
public ClipData getPrimaryClip(){reads++;if(deny)throw new SecurityException("synthetic private URI");return clip;}
public void setPrimaryClip(ClipData c){writes++;if(deny)throw new SecurityException();if(drop)return;clip=c;c.getDescription().time=++time;
if(afterWrite!=null)afterWrite.run();fire();}
public void fire(){for(var l:java.util.List.copyOf(listeners))l.onPrimaryClipChanged();}
public void addPrimaryClipChangedListener(OnPrimaryClipChangedListener l){listeners.add(l);}public void removePrimaryClipChangedListener(OnPrimaryClipChangedListener l){listeners.remove(l);}}
''',
'android/content/Context.java': '''package android.content;public class Context {public String pkg;public ClipboardManager cm=new ClipboardManager();
public Context(String p){pkg=p;}public String getPackageName(){return pkg;}public <T>T getSystemService(Class<T> t){return t.cast(cm);}}''',
'io/github/libxposed/api/XposedInterface.java': '''package io.github.libxposed.api;public interface XposedInterface {interface HookHandle{void unhook();}}''',
'io/github/libxposed/api/XposedModule.java': '''package io.github.libxposed.api;public class XposedModule {
public interface Chain{Object getArg(int n);Object proceed()throws Throwable;}public interface Interceptor{Object run(Chain c)throws Throwable;}
public static Interceptor interceptor;public Builder hook(java.lang.reflect.Method m){return new Builder();}public boolean deoptimize(java.lang.reflect.Method m){return true;}
public static class Builder{public Builder setId(String s){return this;}public XposedInterface.HookHandle intercept(Interceptor i){interceptor=i;return ()->interceptor=null;}}}
''',
'com/xiaomi/mirror/k0.java': 'package com.xiaomi.mirror;public class k0 {}',
'com/xiaomi/mirror/message/ClipDataMessage.java': '''package com.xiaomi.mirror.message;public class ClipDataMessage {public long clock;public ClipDataMessage(){}}''',
'com/xiaomi/mirror/message/ClipboardMessage.java': '''package com.xiaomi.mirror.message;public class ClipboardMessage {public ClipDataMessage data;public ClipboardMessage(){}}''',
'V3/V.java': '''package V3;import android.content.*;import com.xiaomi.mirror.message.*;import io.github.libxposed.api.XposedModule;
public class V {public static final V manager=new V();public static Context context;public long clock;public boolean skipConversion;public int calls,converted;
public static V G(){return manager;}public ClipData z0(ClipDataMessage m,boolean remote){return null;}
public void T(com.xiaomi.mirror.k0 peer,ClipboardMessage msg){calls++;if(msg.data.clock<=clock)return;clock=msg.data.clock;
if(skipConversion)return;try {ClipData clip=(ClipData)XposedModule.interceptor.run(new XposedModule.Chain(){public Object getArg(int n){return n==0?msg.data:Boolean.TRUE;}
public Object proceed(){return z0(msg.data,true);}});converted++;context.cm.setPrimaryClip(clip);}catch(Throwable t){if(t instanceof RuntimeException r)throw r;throw new RuntimeException(t);}}
}
''',
'com/oplus/linker/synergy/wisetransfer/clipboard/ClipBoardServiceHelper.java': '''package com.oplus.linker.synergy.wisetransfer.clipboard;
public class ClipBoardServiceHelper {public static int calls;public static android.content.ClipData previous;
public static void copyCompleteDataToClip(android.content.ClipData clip,android.content.Context c){calls++;previous=clip;c.cm.setPrimaryClip(clip);}}
''',
PKG+'FileTransferUris.java': '''package io.github.yuejie.connect;class FileTransferUris {static void requireContent(android.net.Uri u){
if(u==null||!"content".equals(u.getScheme())||u.getAuthority()==null)throw new IllegalArgumentException("local_content_uri_required");}}''',
}
harness = r'''package io.github.yuejie.connect;
import android.content.*;import android.net.Uri;import android.os.*;import io.github.libxposed.api.XposedModule;
import java.util.*;import java.util.concurrent.*;import org.json.*;
public class ClipboardRichHarness {
 static int checks;static boolean allowed=true;static JSONObject projection;
 static String A="a".repeat(64),B="b".repeat(64);
 interface Call{void run()throws Exception;}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
 static void rejects(Call call,String reason)throws Exception{try{call.run();throw new AssertionError("accepted "+reason);}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){check(reason.equals(expected.getMessage()),"error: "+reason);}}
 static void status(CompletableFuture<ClipboardRichReceiver.Result> f,String error){Handler.drain();check(Objects.equals(error,f.join().error()),"result "+error);}
 static JSONObject p(String id,String s)throws Exception{return new JSONObject().put("id",id).put("session_id",s);}
 static ClipboardRichContent.Html html(){return new ClipboardRichContent.Html("synthetic \u0000 文本","<b>synthetic &amp; 文本</b>");}
 public static void main(String[] args)throws Throwable {
  var html=html();check(ClipboardRichCodec.decode(ClipboardRichCodec.encode(html)).equals(html),"strict UTF8/NUL/HTML pair roundtrip");
  var empty=new ClipboardRichContent.Html("","<b></b>");check(ClipboardRichCodec.decode(ClipboardRichCodec.encode(empty)).equals(empty),"empty fallback preserved");
  rejects(()->new ClipboardRichContent.Html("text",""),"invalid_html_payload");
  rejects(()->new ClipboardRichContent.Html("\ud800","<b>x</b>"),"invalid_html_payload");
  rejects(()->new ClipboardRichContent.Html("x".repeat(4096),"y"),"invalid_html_payload");
  var max=new ClipboardRichContent.Html("x","y".repeat(4095));check(ClipboardRichCodec.encode(max).toString().length()<5700,"worst budget fits bounded Binder envelope");
  JSONObject bad=ClipboardRichCodec.encode(html).put("uri","content://synthetic/item");final var extraField=bad;rejects(()->ClipboardRichCodec.decode(extraField),"invalid_html_payload");
  bad=ClipboardRichCodec.encode(html).put("utf8_b64",3);final var numeric=bad;rejects(()->ClipboardRichCodec.decode(numeric),"invalid_html_payload");
  bad=ClipboardRichCodec.encode(html).put("utf8_b64","eA");final var noPadding=bad;rejects(()->ClipboardRichCodec.decode(noPadding),"invalid_html_payload");
  bad=ClipboardRichCodec.encode(html).put("utf8_b64","/w==");final var invalidUtf8=bad;rejects(()->ClipboardRichCodec.decode(invalidUtf8),"invalid_html_payload");
  check(!new ClipboardRichContent.Html("a","bc").digest().equals(new ClipboardRichContent.Html("ab","c").digest()),"digest binds pair boundary");
  var mixed=html.localClip();mixed.addItem(new ClipData.Item(Uri.parse("content://synthetic/extra")));rejects(()->ClipboardRichContent.snapshot(mixed),"unsupported_clipboard_format");
  for(int source:new int[]{1,2}) {
   Context ctx=new Context(source==1?"com.xiaomi.mirror":"com.oplus.linker");V3.V.context=ctx;V3.V.manager.clock=0;V3.V.manager.skipConversion=false;
   var nativeSink=new ClipboardNativeContent(new XposedModule(),ctx,ClipboardRichHarness.class.getClassLoader(),source);
   var guard=new ClipboardEchoGuard();var events=new ArrayList<ClipboardRichSource.Event>();projection=p("peer",A);allowed=true;
   var observer=new ClipboardRichSource(ctx,guard,()->allowed,()->projection,events::add);observer.update();check(ctx.cm.reads==0,"authorization does not read history");
   var receiver=new ClipboardRichReceiver(nativeSink,guard,()->allowed,()->projection);
   var receive=receiver.executeHtml("peer",A,"write1",html,2000);Handler.one();check(ctx.cm.writes==0&&!receive.isDone(),"admission separate from native execution");
   status(receive,null);check(html.matches(ctx.cm.clip)&&ctx.cm.writes==1,"real importer requires content readback");check(events.isEmpty(),"synchronous native write echo suppressed");
   ctx.cm.fire();check(events.isEmpty(),"delayed received platform event suppressed");
   status(receiver.executeHtml("peer",A,"write1",html,2000),null);check(ctx.cm.writes==1,"same request replay no second write");
   status(receiver.executeHtml("peer",A,"write1",empty,2000),"request_id_reused");
   ctx.cm.setPrimaryClip(html.localClip());check(events.size()==1,"new actual same-content user copy is sent");ctx.cm.fire();check(events.size()==1,"duplicate platform event one source");
   check(events.get(0).peer().equals("peer")&&events.get(0).session().equals(A),"source binds actual peer/session");
   var queued=receiver.executeHtml("peer",A,"cancel",empty,2000);Handler.one();var cancelled=receiver.cancel("peer",A,"cancel");
   // Move cancel ahead of queued apply, modeling a cancellation that wins the native queue.
   var apply=Handler.q.remove();Handler.one();Handler.q.add(apply);Handler.drain();check(cancelled.join(),"queued cancellation wins");status(queued,"cancelled");
   int before=ctx.cm.writes;var expired=receiver.executeHtml("peer",A,"expired",empty,1100);Handler.one();SystemClock.now=1100;status(expired,"request_timeout");check(ctx.cm.writes==before,"expired queue never writes");SystemClock.now=1000;
   var wrong=receiver.executeHtml("other",A,"wrong-peer",empty,2000);status(wrong,"stale_session");
   var stale=receiver.executeHtml("peer",A,"stale",empty,2000);Handler.one();projection=p("peer",B);status(stale,"stale_session");check(ctx.cm.writes==before,"changed session never writes queued content");
   projection=p("peer",A);ctx.cm.drop=true;status(receiver.executeHtml("peer",A,"drop",empty,2000),"clipboard_write_unverified");ctx.cm.drop=false;
   ctx.cm.deny=true;status(receiver.executeHtml("peer",A,"permission",empty,2000),"permission_denied");ctx.cm.deny=false;
   ctx.cm.afterWrite=()->allowed=false;status(receiver.executeHtml("peer",A,"revoke-during",empty,2000),"stale_session");ctx.cm.afterWrite=null;allowed=true;
   int eventBefore=events.size();allowed=false;observer.update();check(ctx.cm.listeners.isEmpty(),"revocation releases only owned listener");ctx.cm.setPrimaryClip(html.localClip());check(events.size()==eventBefore,"revoked source quiet");
   allowed=true;observer.update();check(events.size()==eventBefore,"reauthorization does not export old clipboard");
   var uri=Uri.parse("content://synthetic/local");var actual=nativeSink.writeLocalUri(uri,"application/octet-stream");check(ClipboardNativeContent.matchesUri(actual,uri,"application/octet-stream"),"recipient-local URI imported without text/Parcel");
   rejects(()->nativeSink.writeLocalUri(Uri.parse("file:///private/file"),"text/plain"),"local_content_uri_required");
   Looper.worker=true;rejects(()->nativeSink.writeHtml(html),"clipboard_requires_main");Looper.worker=false;
   receiver.close();status(receiver.executeHtml("peer",A,"closed",html,2000),"stale_session");observer.close();nativeSink.close();
   rejects(()->nativeSink.writeHtml(html),"clipboard_lifecycle");
   if(source==1)check(XposedModule.interceptor==null,"Mirror conversion hook released");
  }
  Context ctx=new Context("com.xiaomi.mirror");V3.V.context=ctx;V3.V.manager.clock=0;
  var sink=new ClipboardNativeContent(new XposedModule(),ctx,ClipboardRichHarness.class.getClassLoader(),1);
  V3.V.manager.skipConversion=true;rejects(()->sink.writeHtml(html),"native_conversion_not_observed");V3.V.manager.skipConversion=false;
  check(XposedModule.interceptor.run(new XposedModule.Chain(){public Object getArg(int n){return n==0?new com.xiaomi.mirror.message.ClipDataMessage():Boolean.TRUE;}public Object proceed(){return "native-unrelated";}}).equals("native-unrelated"),"unrelated native z0 call proceeds unchanged");
  sink.writeHtml(html);long clock=V3.V.manager.clock;sink.writeLocalUri(Uri.parse("content://synthetic/a"),"text/plain");check(V3.V.manager.clock>clock,"different import formats have one monotonic native clock");sink.close();
  System.out.println("PASS "+checks+" rich clipboard assertions; Android/OEM/hook doubles only, no device proof");
 }
}'''


def run(label, *argv):
    result = subprocess.run([str(a) for a in argv], cwd=ROOT, capture_output=True)
    output = result.stdout.decode('utf-8', 'replace') + result.stderr.decode('utf-8', 'replace')
    (OUT / (label + '.txt')).write_text(output, encoding='utf-8')
    if result.returncode:
        raise RuntimeError(f'{label} failed; see {OUT / (label + ".txt")}')
    print(output.strip() or label + ': PASS')


def main():
    sources = OUT / 'sources'
    classes = OUT / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    stubs[PKG+'ClipboardRichHarness.java'] = harness
    for relative, body in stubs.items():
        path = sources / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
    actual = [MAIN / name for name in ['ClipboardRichContent.java', 'ClipboardNativeContent.java',
        'ClipboardNativeClock.java', 'ClipboardEchoGuard.java', 'ClipboardRichCodec.java',
        'ClipboardRichReceiver.java', 'ClipboardRichSource.java', 'OplusClipboardTasks.java',
        'OplusClipboardCodec.java', 'ClipboardWireCodec.java']]
    json_sources = sorted((ROOT / 'build/file-transfer-validation/json-src/org/json').glob('*.java'))
    assert len(json_sources) >= 6, 'Prepare Android JSON sources with scripts/prepare-file-transfer-json.py first'
    run('compile', JDK / 'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-d', classes,
        *actual, *json_sources, *sorted(sources.rglob('*.java')))
    run('rich-tests', JDK / 'java.exe', '-cp', classes, 'io.github.yuejie.connect.ClipboardRichHarness')
    (OUT / 'evidence.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-rich.py',
        source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [*actual, Path(__file__)]},
        generated_test_sha256={str(p.relative_to(OUT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources.rglob('*.java')},
        json_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in json_sources},
        result='passed', apk=None, device_operations=False,
        scope='real rich receive/source/import/codec/echo code with deterministic platform/OEM/hook doubles'), indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
