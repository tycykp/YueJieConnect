"""Exercise actual URI bridge source/negotiation/native receipt glue; Binder/file-wire are doubles."""
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('paste_tests', ROOT / 'scripts/validate-clipboard-uri-paste.py')
paste = importlib.util.module_from_spec(spec)
spec.loader.exec_module(paste)
rich = paste.rich
OUT = ROOT / 'build/clipboard-validation/uri-bridge-host'
rich.OUT = OUT
PKG = paste.PKG
stubs = dict(paste.stubs)
stubs[PKG+'ClipboardBridge.java'] = '''package io.github.qiangjian.connect;class ClipboardBridge{interface Probe{
 boolean permitUriOutbound(android.content.ClipData c);boolean permitUriInbound(String n,long z,String h);
 void attachUriTransfers(java.util.function.Supplier<? extends java.util.concurrent.CompletableFuture<?>> s);
 void uriStaged(String s,String n,android.content.ClipData c);void uriApplied(String s,String n,android.content.ClipData c);void uriConsumed(String s,String n);}
 static Probe activeProbe(){return null;}}'''
stubs['android/content/ClipData.java'] = stubs['android/content/ClipData.java'].replace(
    'public ClipData(ClipDescription d,Item i)', 'public ClipData(ClipData c){this(c.d,c.items.get(0));}public ClipData(ClipDescription d,Item i)')
stubs['android/database/Cursor.java'] = '''package android.database;public interface Cursor extends java.io.Closeable{boolean moveToFirst();int getColumnIndex(String n);boolean isNull(int i);String getString(int i);long getLong(int i);default void close(){}}'''
stubs['android/database/MatrixCursor.java'] = stubs['android/database/MatrixCursor.java'].replace('class MatrixCursor{', 'class MatrixCursor implements Cursor{').replace(
    'public void addRow(Object[] v){values=v;}', '''public void addRow(Object[] v){values=v;}public boolean moveToFirst(){return values!=null;}public int getColumnIndex(String n){return java.util.Arrays.asList(columns).indexOf(n);}public boolean isNull(int i){return values[i]==null;}public String getString(int i){return (String)values[i];}public long getLong(int i){return ((Number)values[i]).longValue();}''')
stubs['android/provider/OpenableColumns.java'] = 'package android.provider;public class OpenableColumns{public static final String DISPLAY_NAME="_display_name",SIZE="_size";}'
stubs['android/content/Context.java'] = stubs['android/content/Context.java'].replace('public String pkg;', 'public java.io.File cache;public java.io.File getCacheDir(){return cache;}public String pkg;')
stubs['android/content/ContentResolver.java'] = stubs['android/content/ContentResolver.java'].replace('public int opens;', '''public String getType(android.net.Uri u){return "application/octet-stream";}public android.database.Cursor query(android.net.Uri u,String[] p,String s,String[] a,String sort){var c=new android.database.MatrixCursor(p);c.addRow(new Object[]{"synthetic.bin",(long)data.get(u).length});return c;}public int opens;''')
stubs['android/util/Log.java'] = 'package android.util;public class Log{public static final int WARN=5;}'
stubs['io/github/libxposed/api/XposedModule.java'] = stubs['io/github/libxposed/api/XposedModule.java'].replace('public boolean deoptimize', 'public void log(int priority,String tag,String message){}public boolean deoptimize')
stubs[PKG+'OplusClipboardEndpoint.java'] = 'package io.github.qiangjian.connect;class OplusClipboardEndpoint{OplusClipboardEndpoint(ClassLoader l){}boolean enabled(android.content.Context c){return true;}}'
stubs[PKG+'XiaomiClipboardEndpoint.java'] = 'package io.github.qiangjian.connect;class XiaomiClipboardEndpoint{XiaomiClipboardEndpoint(ClassLoader l){}boolean available(){return true;}}'
stubs[PKG+'FileTransferUris.java'] = '''package io.github.qiangjian.connect;class FileTransferUris{
 static void requireContent(android.net.Uri uri){if(uri==null||!"content".equals(uri.getScheme()))throw new IllegalArgumentException();}
 static FileTransferSource snapshot(android.content.Context c,android.net.Uri uri,String name,String mime,long size,FileTransferEngine.SessionGate gate,FileTransferEngine.Key key)throws java.io.IOException{
 if(!gate.current(key))throw new java.io.IOException("stale_session");var result=FileTransferSource.snapshot(c.cache,new java.io.ByteArrayInputStream(c.resolver.data.get(uri)),name,mime,size);
 if(!gate.current(key)){result.close();throw new java.io.IOException("stale_session");}return result;}}'''
stubs[PKG+'BridgeClient.java'] = '''package io.github.qiangjian.connect;import org.json.*;import java.util.*;import java.util.function.*;
class BridgeClient{interface Subscription{void close();}interface TaskCompletion{void success(JSONObject d);void fail(String e);}interface VendorExecutor{void execute(JSONObject t,TaskCompletion c);void cancel(String s,String r);void sessionChanged();}
 JSONObject p,payload;String peer,session,op,request;Consumer<JSONObject> response,listener;int requests,cancels;Map<String,VendorExecutor> executors=new HashMap<>();
 JSONObject projection(){return p;}Subscription addProjectionListener(Consumer<JSONObject> l){listener=l;return ()->listener=null;}
 Subscription registerExecutor(String o,VendorExecutor e){executors.put(o,e);return ()->executors.remove(o);}
 void request(String peer,String session,String op,String id,JSONObject data,Consumer<JSONObject> callback){this.peer=peer;this.session=session;this.op=op;request=id;payload=data;response=callback;requests++;}
 void cancel(String s,String r,Consumer<JSONObject> c){cancels++;c.accept(new JSONObject());}}
'''
stubs[PKG+'FileTransferClient.java'] = '''package io.github.qiangjian.connect;class FileTransferClient{int sends;FileTransferSource source;FileTransferEngine.Key key;java.util.concurrent.CompletableFuture<FileTransferEngine.Receipt> result;
 java.util.concurrent.CompletableFuture<FileTransferEngine.Receipt> send(FileTransferEngine.Key k,FileTransferSource s){sends++;source=s;key=k;result=new java.util.concurrent.CompletableFuture<>();result.whenComplete((r,e)->{try{s.close();}catch(java.io.IOException x){}});return result;}}'''
stubs[PKG+'FileTransferEndpoint.java'] = 'package io.github.qiangjian.connect;public class FileTransferEndpoint{final FileTransferEngine engine;FileTransferEndpoint(FileTransferEngine e){engine=e;}}'
stubs[PKG+'FileTransferBridge.java'] = '''package io.github.qiangjian.connect;import java.util.concurrent.*;import org.json.*;
public class FileTransferBridge{public interface Registration{void close();}public interface NativeReceiver{void prepared(FileTransferNegotiation.Prepared p,FileTransferEndpoint e,FileTransferNegotiation n);default void revoked(FileTransferNegotiation.Prepared p){}default void sessionChanged(){}default void close(){}}
 final BridgeClient client;final FileTransferEngine engine;final FileTransferEndpoint endpoint;final FileTransferNegotiation negotiation;final FileTransferClient fileClient=new FileTransferClient();NativeReceiver receiver;int allocations,decisions,releases;CompletableFuture<Void> decision;boolean live=true;
 FileTransferBridge(BridgeClient c,FileTransferEngine e){client=c;engine=e;endpoint=new FileTransferEndpoint(e);negotiation=new FileTransferNegotiation(this::current,()->android.os.SystemClock.now);}
 public boolean ready(){return live;}public boolean current(FileTransferEngine.Key k){return live&&k.peer().equals(client.p.optString("id"))&&k.session().equals(client.p.optString("session_id"));}
 public Registration registerReceiver(FileTransferNegotiation.Purpose p,NativeReceiver r){receiver=r;return ()->receiver=null;}public FileTransferEndpoint endpoint(){return endpoint;}public FileTransferNegotiation negotiation(){return negotiation;}public FileTransferClient fileClient(){return fileClient;}
 public CompletableFuture<FileTransferEngine.Key> allocate(FileTransferNegotiation.Purpose p,String token){allocations++;return CompletableFuture.completedFuture(FileTransferEngine.Key.create(client.p.optString("id"),client.p.optString("session_id")));}
 public CompletableFuture<Void> awaitReceiver(FileTransferEngine.Key k,FileTransferEngine.Offer o){decisions++;decision=new CompletableFuture<>();return decision;}
 public CompletableFuture<Boolean> release(FileTransferEngine.Key k){releases++;return CompletableFuture.completedFuture(true);}}
'''
stubs[PKG+'FileTransferReceiveDocuments.java'] = '''package io.github.qiangjian.connect;import android.content.*;import android.net.*;import java.util.concurrent.*;import java.io.*;import java.nio.file.*;
public class FileTransferReceiveDocuments{static int created;static FileTransferReceiveLease<Uri> last;
 public static FileTransferReceiveLease<Uri> createTemporary(Context c,FileTransferEndpoint endpoint,FileTransferEngine.Key key,FileTransferEngine.Offer offer,FileTransferEngine.SessionGate gate)throws IOException{
 created++;Uri uri=Uri.parse("content://private-target/"+key.transfer());var lease=new FileTransferReceiveLease<Uri>(key,offer,gate,new FileTransferReceiveLease.Destination<Uri>(){
 public Uri value(){return uri;}public void publishVerified(File file,FileTransferEngine.Offer actual,FileTransferEngine.SessionGate g,FileTransferEngine.Key k)throws IOException{c.resolver.data.put(uri,Files.readAllBytes(file.toPath()));}
 public void verify(FileTransferEngine.Offer o,FileTransferEngine.SessionGate g,FileTransferEngine.Key k)throws IOException{byte[] b=c.resolver.data.get(uri);if(b==null||!FileTransferEngine.hex(FileTransferEngine.sha256().digest(b)).equals(o.sha256()))throw new IOException("native_file_hash_mismatch");}
 public void rollback(){c.resolver.data.remove(uri);}},new FileTransferReceiveLease.Authorizer(){
 public CompletableFuture<FileTransferEngine.Authorization> authorize(FileTransferEngine.Key k,FileTransferEngine.Offer o,FileTransferEngine.Sink s){try{return CompletableFuture.completedFuture(endpoint.engine.authorizeTracked(k,o,s));}catch(Exception e){return CompletableFuture.failedFuture(e);}}
 public CompletableFuture<Void> revoke(FileTransferEngine.Key k){try{endpoint.engine.cancel(k);return CompletableFuture.completedFuture(null);}catch(Exception e){return CompletableFuture.failedFuture(e);}}},()->android.os.SystemClock.now);last=lease;lease.authorize();return lease;}}
'''
harness = r'''package io.github.qiangjian.connect;
import android.content.*;import android.net.*;import android.os.*;import io.github.libxposed.api.XposedModule;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;import org.json.*;
public class ClipboardUriBridgeHarness{
 static int checks;static String A="a".repeat(64);static byte[] BYTES={0,1,2,3,(byte)255};
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;}
 static void waitFor(BooleanSupplier b)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!b.getAsBoolean()){Handler.drain();if(System.nanoTime()>end)throw new AssertionError("wait");Thread.sleep(2);}Handler.drain();}
 static class Done implements BridgeClient.TaskCompletion{JSONObject data;String error;public void success(JSONObject d){data=d;}public void fail(String e){error=e;}}
 static class Fixture implements AutoCloseable{
  final Context c;final File root;final BridgeClient client=new BridgeClient();final FileTransferEngine engine;final FileTransferBridge files;final ClipboardUriBridge bridge;final Uri local=Uri.parse("content://synthetic-source/item");
  Fixture(int brand)throws Exception{
   SystemClock.now=1000;c=new Context(brand==1?"com.xiaomi.mirror":"com.oplus.linker");root=Files.createTempDirectory("qj-uri-glue").toFile();c.cache=new File(root,"snapshots");c.resolver.data.put(local,BYTES);V3.V.context=c;V3.V.manager.clock=0;
   client.p=new JSONObject().put("id","peer").put("session_id",A).put("operations",new JSONArray().put("clipboard_uri_write"));
   engine=new FileTransferEngine(new File(root,"engine"),k->true,()->SystemClock.now);files=new FileTransferBridge(client,engine);
   bridge=new ClipboardUriBridge(new XposedModule(),c,getClass().getClassLoader(),client,()->true,brand,files);Handler.drain();
  }
  void copy() {c.cm.setPrimaryClip(new ClipData("synthetic",new String[]{"application/octet-stream"},new ClipData.Item(local)));Handler.drain();}
  JSONObject reply()throws Exception{var offer=files.fileClient.source.offer();return new JSONObject().put("status","ok").put("session_id",client.session).put("request_id",client.request)
   .put("result",new JSONObject().put("operation",client.op).put("data",new JSONObject().put("transfer_id",files.fileClient.key.transfer()).put("mime",offer.mime()).put("size",offer.size()).put("sha256",offer.sha256()).put("applied",true)));}
  void upload()throws Exception{waitFor(()->files.decisions>0);check(files.fileClient.sends==0,"no byte send before actual recipient decision");files.decision.complete(null);waitFor(()->files.fileClient.sends>0);
   var offer=files.fileClient.source.offer();files.fileClient.result.complete(new FileTransferEngine.Receipt(files.fileClient.key,offer.size(),offer.sha256()));waitFor(()->client.requests>0);}
  FileTransferNegotiation.Prepared incoming()throws Exception{var key=FileTransferEngine.Key.create("peer",A);var offer=new FileTransferEngine.Offer("synthetic.bin","application/octet-stream",BYTES.length,FileTransferEngine.hex(FileTransferEngine.sha256().digest(BYTES)));
   var p=files.negotiation.prepare(key,new FileTransferNegotiation.Metadata(FileTransferNegotiation.Purpose.CLIPBOARD,UUID.randomUUID().toString().replace("-",""),offer)).prepared();files.receiver.prepared(p,files.endpoint,files.negotiation);return p;}
  CompletableFuture<ParcelFileDescriptor> open()throws Exception{Object provider=Class.forName(c.pm.provider.name).getConstructor().newInstance();Uri uri=c.cm.clip.getItemAt(0).getUri();return CompletableFuture.supplyAsync(()->{try{return (ParcelFileDescriptor)XposedModule.call(provider,"openFile",new Class<?>[]{Uri.class,String.class,CancellationSignal.class},uri,"r",null);}catch(Throwable t){throw new CompletionException(t);}});}
  JSONObject task(FileTransferNegotiation.Prepared p,String wire)throws Exception{return new JSONObject().put("projection_id","peer").put("session_id",A).put("request_id",wire).put("deadline_elapsed_ms",31000).put("payload",ClipboardUriCodec.encode(new ClipboardUriCodec.Request(p.key().transfer(),p.metadata().offer())));}
  boolean approved(FileTransferNegotiation.Prepared p){try{return FileTransferReceiveDocuments.last!=null&&files.negotiation.prepare(p.key(),p.metadata()).approved();}catch(IOException waiting){return false;}}
  public void close()throws Exception{bridge.close();Handler.drain();files.live=false;files.negotiation.close();engine.close();waitFor(()->FileTransferReceiveDocuments.last==null||FileTransferReceiveDocuments.last.disposed());Thread.sleep(30);Handler.drain();try(var ps=Files.walk(root.toPath())){for(var p:ps.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}Handler.delayed.clear();FileTransferReceiveDocuments.last=null;FileTransferReceiveDocuments.created=0;}
 }
 public static void main(String[] args)throws Exception{
  Looper.getMainLooper();
  for(int brand:new int[]{1,2}){
   try(var f=new Fixture(brand)){
    check(f.c.cm.reads==0&&f.files.allocations==0,"setup never exports clipboard history");f.copy();var actual=f.bridge.lastTransfer();f.upload();
    check(ClipboardUriCodec.decode(f.client.payload).offer().sha256().equals(f.files.fileClient.source.offer().sha256()),"measured immutable file offer used for applied RPC");
    check(!actual.isDone()&&f.client.op.equals("clipboard_uri_write"),"file upload alone is not clipboard success");
    f.client.response.accept(f.reply().put("session_id","b".repeat(64)));waitFor(actual::isDone);check(actual.isCompletedExceptionally()&&f.client.cancels==1,"wrong-session ACK rejected and actual cancel issued");waitFor(()->f.files.releases>0);
   }
   try(var f=new Fixture(brand)){
    f.copy();var actual=f.bridge.lastTransfer();f.upload();f.client.response.accept(f.reply());waitFor(actual::isDone);
    check(actual.join().size()==BYTES.length&&actual.join().sha256().equals(f.files.fileClient.source.offer().sha256()),"only matching real remote receipt completes source");waitFor(()->f.files.releases>0);
   }
   try(var f=new Fixture(brand)){
    f.copy();waitFor(()->f.files.decisions>0);var actual=f.bridge.lastTransfer();f.c.cm.setPrimaryClip(new ClipData("changed",new String[]{"text/plain"},new ClipData.Item("new user copy",null,null,null)));Handler.drain();waitFor(actual::isDone);
    check(actual.isCompletedExceptionally()&&f.files.decision.isCancelled(),"new source event cancels previous pending consent");check(f.files.fileClient.sends==0,"late approval cannot send superseded URI");
   }
   try(var f=new Fixture(brand)){
    var prepared=f.incoming();check(FileTransferReceiveDocuments.created==0&&f.files.allocations==0,"authenticated metadata only stages native URI, no destination/grant/echo");
    var opened=f.open();waitFor(()->f.approved(prepared));
    var done=new Done();f.client.executors.get("clipboard_uri_write").execute(f.task(prepared,"83"),done);Handler.drain();check(done.data==null&&done.error==null,"wire numeric task waits for real commit");
    f.engine.begin(prepared.key(),prepared.metadata().offer());f.engine.append(prepared.key(),0,BYTES);f.engine.commit(prepared.key());waitFor(()->done.data!=null||done.error!=null);waitFor(opened::isDone);
    check(done.error==null&&ClipboardUriCodec.matchesAck(done.data,prepared.key(),prepared.metadata().offer()),"wire request maps to canonical exact import result");
    check(Arrays.equals(opened.join().bytes,BYTES)&&f.c.cm.writes==2,"native target reads actual bytes after single stage and single OEM applied write");check(f.files.allocations==0,"incoming native URI import has no echo source allocation");
   }
   try(var f=new Fixture(brand)){
    var prepared=f.incoming();var opened=f.open();waitFor(()->f.approved(prepared));
    var done=new Done();var executor=f.client.executors.get("clipboard_uri_write");executor.execute(f.task(prepared,"84"),done);Handler.drain();executor.cancel(A,"84");Handler.drain();waitFor(()->done.error!=null);waitFor(opened::isDone);
    check("cancelled".equals(done.error)&&opened.isCompletedExceptionally(),"wire numeric cancellation reaches canonical import and genuine pending provider");check(f.c.cm.writes==1,"cancelled paste never publishes a completed URI");
   }
  }
  System.out.println("PASS "+checks+" URI bridge source/real lease/native applied/strict receipt assertions; Binder/file-wire doubles, no device proof");
 }
}'''


def main():
    sources, classes = OUT / 'sources', OUT / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    stubs[PKG+'ClipboardUriBridgeHarness.java'] = harness
    for relative, body in stubs.items():
        path = sources / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
    names = ['ClipboardUriBridge','ClipboardUriSource','ClipboardUriTransfer','ClipboardUriPasteHooks','ClipboardUriRoutes',
             'ClipboardUriCodec','ClipboardUriReceiver','ClipboardNativeContent','ClipboardNativeClock','ClipboardEchoGuard',
             'ClipboardRichContent','FileTransferSource','FileTransferEngine','FileTransferReceiveLease','FileTransferNegotiation']
    actual = [rich.MAIN / (name+'.java') for name in names]
    files = [*actual, Path(__file__), Path(paste.__file__), Path(rich.__file__)]
    inputs = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    json_sources = sorted((ROOT / 'build/file-transfer-validation/json-src/org/json').glob('*.java'))
    rich.run('compile',rich.JDK/'javac.exe','--release','17','-encoding','UTF-8','-d',classes,*actual,*json_sources,*sorted(sources.rglob('*.java')))
    rich.run('bridge-tests',rich.JDK/'java.exe','-cp',classes,'io.github.qiangjian.connect.ClipboardUriBridgeHarness')
    races = [p for p,sha in inputs.items() if hashlib.sha256((ROOT/p).read_bytes()).hexdigest()!=sha]
    (OUT/'evidence.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-uri-bridge.py',result='passed' if not races else 'source_changed',
        source_sha256=inputs,source_races=races,apk=None,device_operations=False),indent=2),encoding='utf-8')
    assert not races


if __name__ == '__main__':
    main()
