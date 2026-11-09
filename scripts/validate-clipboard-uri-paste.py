"""New native provider/paste path, with actual file engine/lease/URI receiver and OEM/OS doubles."""
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('rich', ROOT / 'scripts/validate-clipboard-rich.py')
rich = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rich)
OUT = ROOT / 'build/clipboard-validation/uri-paste-host'
rich.OUT = OUT
PKG = rich.PKG
stubs = dict(rich.stubs)
stubs['android/net/Uri.java'] = '''package android.net;public record Uri(String value){public static Uri parse(String s){return new Uri(s);}
 public String getScheme(){return value.split(":")[0];}public String getAuthority(){return value.split("://")[1].split("/")[0];}
 public java.util.List<String> getPathSegments(){String s=value.split("://")[1];int slash=s.indexOf('/');return slash<0?java.util.List.of():java.util.Arrays.asList(s.substring(slash+1).split("[?]")[0].split("/"));}
 public static class Builder{String s,a,p="",q="";public Builder scheme(String x){s=x;return this;}public Builder authority(String x){a=x;return this;}
 public Builder appendPath(String x){p+="/"+x;return this;}public Builder appendQueryParameter(String k,String v){q="?"+k+"="+v;return this;}public Uri build(){return new Uri(s+"://"+a+p+q);}}}'''
stubs['android/os/Process.java'] = 'package android.os;public class Process{public static int myUid(){return 10001;}public static int myPid(){return 100;}}'
stubs['android/os/Binder.java'] = '''package android.os;public class Binder{public static ThreadLocal<Boolean> cleared=ThreadLocal.withInitial(()->false);
 public static int uid=30001,pid=1234;public static int getCallingUid(){return cleared.get()?Process.myUid():uid;}
 public static int getCallingPid(){return cleared.get()?Process.myPid():pid;}public static long clearCallingIdentity(){cleared.set(true);return 1;}
 public static void restoreCallingIdentity(long token){cleared.set(false);}}'''
stubs['android/os/CancellationSignal.java'] = 'package android.os;public class CancellationSignal{volatile boolean cancelled;public boolean isCanceled(){return cancelled;}public void cancel(){cancelled=true;}}'
stubs['android/os/ParcelFileDescriptor.java'] = '''package android.os;public class ParcelFileDescriptor implements java.io.Closeable{
 public final byte[] bytes;public boolean closed;public ParcelFileDescriptor(byte[] b){bytes=b.clone();}public void close(){closed=true;}}'''
stubs['android/content/Intent.java'] = 'package android.content;public class Intent{public static final int FLAG_GRANT_READ_URI_PERMISSION=1;}'
stubs['android/content/pm/ProviderInfo.java'] = 'package android.content.pm;public class ProviderInfo{public String packageName,name;public boolean exported,grantUriPermissions=true;}'
stubs['android/content/pm/PackageManager.java'] = '''package android.content.pm;public class PackageManager{public static final int PERMISSION_GRANTED=0;public ProviderInfo provider;
 public ProviderInfo resolveContentProvider(String a,int flags){return provider;}}'''
stubs['android/database/MatrixCursor.java'] = '''package android.database;public class MatrixCursor{public final String[] columns;public Object[] values;
 public MatrixCursor(String[] c){columns=c;}public void addRow(Object[] v){values=v;}}'''
stubs['android/content/ContentResolver.java'] = '''package android.content;public class ContentResolver{
 public java.util.Map<android.net.Uri,byte[]> data=new java.util.HashMap<>();public int opens;public boolean cleared;
 public android.os.ParcelFileDescriptor openFileDescriptor(android.net.Uri uri,String mode)throws java.io.IOException{
 cleared=android.os.Binder.cleared.get();if(!cleared)throw new SecurityException("private target identity");opens++;
 byte[] b=data.get(uri);if(b==null)throw new java.io.FileNotFoundException();return new android.os.ParcelFileDescriptor(b);}}'''
stubs['android/content/Context.java'] = '''package android.content;public class Context {public String pkg;public ClipboardManager cm=new ClipboardManager();
 public boolean grant=true;public int lastGrantUid,lastGrantPid;public android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public ContentResolver resolver=new ContentResolver();
 public Context(String p){pkg=p;pm.provider=new android.content.pm.ProviderInfo();pm.provider.packageName=p;pm.provider.name=p.equals("com.xiaomi.mirror")?"com.xiaomi.mirror.provider.RemoteProvider":"com.oplus.linker.synergy.wisetransfer.clipboard.provider.ClipboardProvider";pm.provider.exported=p.equals("com.xiaomi.mirror");}
 public String getPackageName(){return pkg;}public <T>T getSystemService(Class<T> t){return t.cast(cm);}public android.content.pm.PackageManager getPackageManager(){return pm;}
 public ContentResolver getContentResolver(){return resolver;}public int checkUriPermission(android.net.Uri u,int pid,int uid,int flags){lastGrantUid=uid;lastGrantPid=pid;return grant?0:-1;}}'''
stubs['android/content/ContentProvider.java'] = '''package android.content;public class ContentProvider{public static String callingPackage="com.android.fileexplorer";public int originals;
 public String getCallingPackage(){return callingPackage;}public android.os.ParcelFileDescriptor openFile(android.net.Uri u,String m){originals++;return null;}
 public android.os.ParcelFileDescriptor openFile(android.net.Uri u,String m,android.os.CancellationSignal c){originals++;return null;}
 public String getType(android.net.Uri u){originals++;return "native-unrelated";}
 public android.database.MatrixCursor query(android.net.Uri u,String[] p,String s,String[] a,String sort){originals++;return null;}
 public android.database.MatrixCursor query(android.net.Uri u,String[] p,String s,String[] a,String sort,android.os.CancellationSignal c){originals++;return null;}}'''
stubs['com/xiaomi/mirror/provider/RemoteProvider.java'] = '''package com.xiaomi.mirror.provider;public class RemoteProvider extends android.content.ContentProvider{public static boolean allow=true;public boolean f(String p){return allow;}}'''
stubs['com/oplus/linker/synergy/wisetransfer/clipboard/provider/ClipboardProvider.java'] = 'package com.oplus.linker.synergy.wisetransfer.clipboard.provider;public class ClipboardProvider extends android.content.ContentProvider{}'
stubs['io/github/libxposed/api/XposedModule.java'] = '''package io.github.libxposed.api;public class XposedModule{
 public interface Chain{Object getArg(int i);Object proceed()throws Throwable;default Object getThisObject(){return null;}}
 public interface Interceptor{Object run(Chain c)throws Throwable;}public static Interceptor interceptor;public static java.util.Map<java.lang.reflect.Method,Interceptor> hooks=new java.util.HashMap<>();
 public boolean deoptimize(java.lang.reflect.Method m){return true;}public Builder hook(java.lang.reflect.Method m){return new Builder(m);}
 public static class Builder{final java.lang.reflect.Method method;Builder(java.lang.reflect.Method m){method=m;}public Builder setId(String id){return this;}
 public XposedInterface.HookHandle intercept(Interceptor i){if(method.getName().equals("z0")){interceptor=i;return ()->interceptor=null;}hooks.put(method,i);return ()->hooks.remove(method);}}
 public static Object call(Object instance,String name,Class<?>[] types,Object...args)throws Throwable{
 java.lang.reflect.Method m=instance.getClass().getMethod(name,types);Interceptor h=hooks.get(m);Chain chain=new Chain(){public Object getArg(int n){return args[n];}public Object getThisObject(){return instance;}
 public Object proceed()throws Throwable{return m.invoke(instance,args);}};return h==null?chain.proceed():h.run(chain);}}'''
harness = r'''package io.github.yuejie.connect;
import android.content.*;import android.net.Uri;import android.os.*;import io.github.libxposed.api.XposedModule;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;import org.json.*;
public class ClipboardUriPasteHarness{
 static int checks;static String A="a".repeat(64);static byte[] BYTES={0,1,2,3,(byte)255};
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
 static void waitFor(BooleanSupplier b)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!b.getAsBoolean()){Handler.drain();if(System.nanoTime()>end)throw new AssertionError("wait");Thread.sleep(2);}Handler.drain();}
 static Object call(ContentProvider p,String name,Class<?>[] t,Object...a)throws Throwable{return XposedModule.call(p,name,t,a);}
 static Class<?>[] OPEN={Uri.class,String.class,CancellationSignal.class};
 static CompletableFuture<ParcelFileDescriptor> open(ContentProvider p,Uri u,String mode,CancellationSignal c){return CompletableFuture.supplyAsync(()->{try{return (ParcelFileDescriptor)call(p,"openFile",OPEN,u,mode,c);}catch(Throwable t){throw new CompletionException(t);}});}
 static void denied(CompletableFuture<?> f)throws Exception{waitFor(f::isDone);check(f.isCompletedExceptionally(),"native open denied");}
 static final class Fixture implements AutoCloseable{
  final Context context;final File root;final FileTransferEngine engine;final FileTransferEngine.Key key=FileTransferEngine.Key.create("peer",A);
  final FileTransferEngine.Offer offer=new FileTransferEngine.Offer("synthetic.bin","application/octet-stream",BYTES.length,FileTransferEngine.hex(FileTransferEngine.sha256().digest(BYTES)));
  final Uri local=Uri.parse("content://private-target/"+key.transfer());final ClipboardNativeContent endpoint;final ClipboardEchoGuard guard=new ClipboardEchoGuard();final ClipboardUriRoutes routes;
  final ClipboardUriPasteHooks hooks;final ContentProvider provider;final ClipboardUriPasteHooks.Prepared prepared;
  volatile boolean live=true;volatile int receives,claims,verifies,revokes;volatile FileTransferReceiveLease<Uri> lease;volatile boolean deleted;final CompletableFuture<ClipboardUriReceiver.Result> applied=new CompletableFuture<>();
  Fixture(int brand)throws Exception{
   SystemClock.now=1000;context=new Context(brand==1?"com.xiaomi.mirror":"com.oplus.linker");root=Files.createTempDirectory("yj-uri-paste").toFile();engine=new FileTransferEngine(root,k->live,()->SystemClock.now);
   V3.V.context=context;V3.V.manager.clock=0;endpoint=new ClipboardNativeContent(new XposedModule(),context,getClass().getClassLoader(),brand);
   JSONObject projection=new JSONObject().put("id","peer").put("session_id",A);routes=new ClipboardUriRoutes(endpoint,guard,()->live,()->projection);
   prepared=new ClipboardUriPasteHooks.Prepared(key,offer,"b".repeat(32));
   hooks=new ClipboardUriPasteHooks(new XposedModule(),context,getClass().getClassLoader(),brand,endpoint,guard,new ClipboardUriPasteHooks.Routes(){
    public boolean current(ClipboardUriPasteHooks.Prepared p){return live&&p.equals(prepared);}
    public CompletableFuture<ClipboardUriPasteHooks.Received> receive(ClipboardUriPasteHooks.Prepared p,ClipboardUriPasteHooks.Caller caller){receives++;
     lease=new FileTransferReceiveLease<>(key,offer,k->live,new FileTransferReceiveLease.Destination<Uri>(){
      public Uri value(){return local;}public void publishVerified(File staged,FileTransferEngine.Offer o,FileTransferEngine.SessionGate gate,FileTransferEngine.Key k)throws IOException{context.resolver.data.put(local,Files.readAllBytes(staged.toPath()));}
      public void verify(FileTransferEngine.Offer o,FileTransferEngine.SessionGate gate,FileTransferEngine.Key k)throws IOException{check(Looper.myLooper()!=Looper.getMainLooper(),"rehash off main");verifies++;byte[] data=context.resolver.data.get(local);if(data==null||!FileTransferEngine.hex(FileTransferEngine.sha256().digest(data)).equals(o.sha256()))throw new IOException("native_file_hash_mismatch");}
      public void rollback(){context.resolver.data.remove(local);deleted=true;}
     },new FileTransferReceiveLease.Authorizer(){
      public CompletableFuture<FileTransferEngine.Authorization> authorize(FileTransferEngine.Key k,FileTransferEngine.Offer o,FileTransferEngine.Sink sink){try{return CompletableFuture.completedFuture(engine.authorizeTracked(k,o,sink));}catch(Exception e){return CompletableFuture.failedFuture(e);}}
      public CompletableFuture<Void> revoke(FileTransferEngine.Key k){try{engine.cancel(k);return CompletableFuture.completedFuture(null);}catch(Exception e){return CompletableFuture.failedFuture(e);}}
     },()->SystemClock.now);lease.authorize();
     CompletableFuture<ClipboardUriPasteHooks.Received> result=new CompletableFuture<>();Handler h=new Handler(Looper.getMainLooper());
     h.post(()->{check(routes.attach(lease),"single accepted native lease handoff");result.complete(new ClipboardUriPasteHooks.Received(lease,applied));});
     lease.committed().whenComplete((receipt,error)->h.post(()->{if(error!=null)applied.completeExceptionally(error);else routes.execute("peer",A,prepared.request(),new ClipboardUriCodec.Request(key.transfer(),offer),SystemClock.now+30000).whenComplete((r,e)->{if(e==null)applied.complete(r);else applied.completeExceptionally(e);});}));
     return result;
    }
    public void revoke(ClipboardUriPasteHooks.Prepared p){revokes++;new Handler(Looper.getMainLooper()).post(()->routes.revoke(p.key()));}
   });hooks.install();provider=(ContentProvider)Class.forName(context.pm.provider.name).getConstructor().newInstance();
  }
  Uri stage()throws Exception{check(hooks.stage(prepared),"native placeholder staged");return context.cm.clip.getItemAt(0).getUri();}
  void commit()throws Exception{engine.begin(key,offer);engine.append(key,0,BYTES);engine.commit(key);}
  public void close()throws Exception{hooks.close();routes.close();waitFor(()->lease==null||deleted);endpoint.close();engine.close();try(var f=Files.walk(root.toPath())){for(var p:f.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}Handler.delayed.clear();}
 }
 public static void main(String[] args)throws Throwable{
  Looper.getMainLooper();
  for(int brand:new int[]{1,2}){
   try(var f=new Fixture(brand)){
    Uri u=f.stage();check(f.receives==0&&f.lease==null,"staging never authorizes bytes");
    check(call(f.provider,"getType",new Class<?>[]{Uri.class},u).equals(f.offer.mime()),"native getType metadata");
    var cursor=(android.database.MatrixCursor)call(f.provider,"query",new Class<?>[]{Uri.class,String[].class,String.class,String[].class,String.class},u,null,null,null,null);
    check(cursor.values[0].equals(f.offer.name())&&cursor.values[1].equals(f.offer.size()),"native query name and exact size");check(f.receives==0,"metadata queries do not approve");
    check(call(f.provider,"getType",new Class<?>[]{Uri.class},Uri.parse("content://unrelated/native")).equals("native-unrelated"),"unrelated OEM path unchanged");
    f.context.grant=false;denied(open(f.provider,u,"r",null));check(f.receives==0,"no OS read grant means no decision");f.context.grant=true;
    denied(open(f.provider,u,"rw",null));CancellationSignal cancelled=new CancellationSignal();cancelled.cancel();denied(open(f.provider,u,"r",cancelled));check(f.receives==0,"write or already-cancelled opens do not decide");
    if(brand==1){com.xiaomi.mirror.provider.RemoteProvider.allow=false;denied(open(f.provider,u,"r",null));check(f.receives==0,"Mirror native caller policy preserved");com.xiaomi.mirror.provider.RemoteProvider.allow=true;}
    var reading=open(f.provider,u,"r",null);waitFor(()->f.lease!=null&&f.lease.accepted().isDone());Handler.drain();check(!reading.isDone(),"real native open waits for actual file commit");
    f.commit();waitFor(reading::isDone);var descriptor=reading.join();check(Arrays.equals(descriptor.bytes,BYTES),"actual native PFD serves committed binary bytes");
    check(f.applied.join().applied()&&f.verifies>=2,"native clipboard applied/readback plus provider reopen hash");
    check(f.context.resolver.cleared&&!Binder.cleared.get(),"private destination read clears and restores caller identity");
    var repeat=open(f.provider,u,"r",null);waitFor(repeat::isDone);check(Arrays.equals(repeat.join().bytes,BYTES)&&f.receives==1,"same native target repeated open reuses one decision");
    Binder.uid=30002;denied(open(f.provider,u,"r",null));Binder.uid=30001;check(f.receives==1,"different target cannot reuse prior native decision");
    f.context.resolver.data.put(f.local,new byte[]{9});denied(open(f.provider,u,"r",null));check(f.context.resolver.opens==2,"post-commit mutation never leaks corrupted PFD");
   }
   try(var f=new Fixture(brand)){
    Uri u=f.stage();var reading=open(f.provider,u,"r",null);waitFor(()->f.lease!=null&&f.lease.accepted().isDone());f.live=false;f.hooks.sessionChanged();f.routes.sessionChanged();denied(reading);check(f.revokes==1,"session revocation reaches the existing resource owner");
   }
  }
  System.out.println("PASS "+checks+" native URI-paste/provider/engine assertions; OS/OEM doubles only, no device proof");
 }
}'''


def main():
    sources, classes = OUT / 'sources', OUT / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    stubs[PKG + 'ClipboardUriPasteHarness.java'] = harness
    for relative, body in stubs.items():
        path = sources / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
    names = ['ClipboardUriPasteHooks', 'ClipboardNativeContent', 'ClipboardNativeClock', 'ClipboardEchoGuard',
             'ClipboardRichContent', 'ClipboardUriReceiver', 'ClipboardUriRoutes', 'ClipboardUriCodec', 'FileTransferEngine', 'FileTransferReceiveLease']
    actual = [rich.MAIN / (name + '.java') for name in names]
    files = [*actual, Path(__file__), Path(rich.__file__)]
    inputs = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    json_sources = sorted((ROOT / 'build/file-transfer-validation/json-src/org/json').glob('*.java'))
    rich.run('compile', rich.JDK / 'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-d', classes,
             *actual, *json_sources, *sorted(sources.rglob('*.java')))
    rich.run('paste-tests', rich.JDK / 'java.exe', '-cp', classes, 'io.github.yuejie.connect.ClipboardUriPasteHarness')
    races = [p for p, sha in inputs.items() if hashlib.sha256((ROOT / p).read_bytes()).hexdigest() != sha]
    (OUT / 'evidence.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-uri-paste.py',
        result='passed' if not races else 'source_changed', source_sha256=inputs, source_races=races,
        apk=None, device_operations=False), indent=2), encoding='utf-8')
    assert not races


if __name__ == '__main__':
    main()
