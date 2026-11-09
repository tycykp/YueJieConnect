"""URI clipboard receive integration with the actual file engine/lease and native adapter.
No file consent is manufactured by clipboard: fixtures explicitly create synthetic local decisions.
"""
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('clipboard_rich_tests', ROOT / 'scripts/validate-clipboard-rich.py')
rich = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rich)
OUT = ROOT / 'build/clipboard-validation/uri-receiver-host'
rich.OUT = OUT
PKG = rich.PKG
harness = r'''package io.github.yuejie.connect;
import android.content.*;import android.net.Uri;import android.os.*;import io.github.libxposed.api.XposedModule;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;import org.json.*;
public class ClipboardUriReceiverHarness {
 static int checks;static boolean allowed=true;static JSONObject projection;static final Thread MAIN=Thread.currentThread();
 static String A="a".repeat(64);static byte[] BYTES={0,1,2,(byte)255};
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
 static void waitFor(BooleanSupplier condition)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
  while(!condition.getAsBoolean()){Handler.drain();if(System.nanoTime()>until)throw new AssertionError("bounded wait timed out");Thread.sleep(2);}Handler.drain();}
 static void status(CompletableFuture<ClipboardUriReceiver.Result> result,String error)throws Exception {
  waitFor(result::isDone);check(Objects.equals(error,result.join().error()),"URI result "+error);}
 static class FileFixture implements AutoCloseable {
  final File root;final FileTransferEngine.Key key=FileTransferEngine.Key.create("peer",A);
  final FileTransferEngine.Offer offer=new FileTransferEngine.Offer("synthetic.bin","application/octet-stream",BYTES.length,FileTransferEngine.hex(FileTransferEngine.sha256().digest(BYTES)));
  final Uri uri=Uri.parse("content://synthetic/"+key.transfer());final FileTransferEngine engine;final FileTransferReceiveLease<Uri> lease;
  volatile boolean nativeCurrent=true,deleted,mutated,block,failCleanup;volatile int claims;byte[] published;
  boolean onClipboard=true;int readbacks;Runnable onReadback=()->{};
  final CountDownLatch claimEntered=new CountDownLatch(1),claimRelease=new CountDownLatch(1);
  FileFixture()throws Exception {
   root=Files.createTempDirectory("yj-uri-receive-").toFile();engine=new FileTransferEngine(root,k->nativeCurrent,()->SystemClock.now);
   lease=new FileTransferReceiveLease<>(key,offer,k->nativeCurrent,new FileTransferReceiveLease.Destination<Uri>(){
    public Uri value(){return uri;}
    public void publishVerified(File file,FileTransferEngine.Offer o,FileTransferEngine.SessionGate gate,FileTransferEngine.Key k)throws IOException{published=Files.readAllBytes(file.toPath());}
    public void verify(FileTransferEngine.Offer o,FileTransferEngine.SessionGate gate,FileTransferEngine.Key k)throws IOException{
      check(Thread.currentThread()!=MAIN,"local URI rehash stays off native main");claims++;claimEntered.countDown();
      if(block)try{if(!claimRelease.await(3,TimeUnit.SECONDS))throw new IOException("fixture timeout");}catch(InterruptedException e){throw new IOException(e);}
      if(mutated)throw new IOException("native_file_hash_mismatch");if(!nativeCurrent)throw new IOException("stale_session");}
    public void rollback()throws IOException{if(failCleanup){failCleanup=false;throw new IOException("synthetic cleanup failed");}deleted=true;}
   },new FileTransferReceiveLease.Authorizer(){
    public CompletableFuture<FileTransferEngine.Authorization> authorize(FileTransferEngine.Key k,FileTransferEngine.Offer o,FileTransferEngine.Sink s){
      try{return CompletableFuture.completedFuture(engine.authorizeTracked(k,o,s));}catch(Exception e){return CompletableFuture.failedFuture(e);}}
    public CompletableFuture<Void> revoke(FileTransferEngine.Key k){try{engine.cancel(k);return CompletableFuture.completedFuture(null);}catch(Exception e){return CompletableFuture.failedFuture(e);}}
   },()->SystemClock.now);lease.authorize();lease.accepted().join();
  }
  void commit()throws Exception {engine.begin(key,offer);engine.append(key,0,BYTES);engine.commit(key);}
  boolean verifyClipboard(){check(Thread.currentThread()==MAIN,"clipboard readback runs on main");readbacks++;onReadback.run();return onClipboard;}
  public void close()throws Exception {lease.close();engine.close();try(var files=Files.walk(root.toPath())){for(var p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }
 static Context context;
 static ClipboardUriReceiver receiver(FileFixture f){return new ClipboardUriReceiver(f.lease,f::verifyClipboard,()->allowed,()->projection);}
 static void reset(int brand)throws Exception {SystemClock.now=1000;allowed=true;projection=new JSONObject().put("id","peer").put("session_id",A);
  context=new Context(brand==1?"com.xiaomi.mirror":"com.oplus.linker");}
 public static void main(String[] args)throws Exception {
  for(int brand:new int[]{1,2}) {
   reset(brand);
   try(var f=new FileFixture()) {
    var r=receiver(f);var result=r.execute("uri-one",2000);Handler.drain();check(!result.isDone()&&context.cm.writes==0,"authorization without final commit cannot apply clipboard");
    f.commit();status(result,null);check(f.claims==1&&Arrays.equals(f.published,BYTES),"actual file commit and single local rehash");
    check(f.readbacks==1&&context.cm.writes==0,"native verification retains the staged URI grant");
    check(!f.deleted&&f.lease.current(),"successful URI retained for real paste lifetime");
    check(result.join().transfer().equals(f.key.transfer())&&result.join().sha256().equals(f.offer.sha256())&&result.join().size()==BYTES.length,"receipt binds transfer/measured size/hash without local URI");
    var replay=r.execute("uri-one",2000);status(replay,null);check(f.readbacks==1&&f.claims==1,"same receive instance replay does not apply twice");
    status(r.execute("different-request",2000),"clipboard_uri_already_started");var cancel=r.cancel();Handler.drain();check(!cancel.join(),"completed URI never falsely rolled back");
    r.close();waitFor(()->f.deleted);check(!f.lease.current(),"owner close revokes lease and removes exact new destination");
   }
   try(var f=new FileFixture()) {
    int before=context.cm.writes;var r=receiver(f);var result=r.execute("cancel-before-file",2000);Handler.drain();var cancel=r.cancel();Handler.drain();check(cancel.join(),"cancel waits for no native write");status(result,"cancelled");waitFor(()->f.deleted);check(context.cm.writes==before,"pending file cancellation cannot write URI");
   }
   try(var f=new FileFixture()) {
    int before=context.cm.writes;f.commit();f.block=true;var r=receiver(f);var result=r.execute("cancel-rehash",2000);Handler.drain();waitFor(()->f.claims==1);
    var cancel=r.cancel();Handler.drain();check(cancel.join(),"cancel may win while provider verification is running");f.claimRelease.countDown();status(result,"cancelled");waitFor(()->f.deleted);check(context.cm.writes==before,"late claim cannot overwrite clipboard after cancellation");
   }
   try(var f=new FileFixture()) {
    int before=context.cm.writes;f.commit();f.mutated=true;var r=receiver(f);status(r.execute("mutated",2000),"clipboard_uri_claim_failed");waitFor(()->f.deleted);check(context.cm.writes==before,"mutation after file receipt prevents native import");
   }
   try(var f=new FileFixture()) {
    int before=context.cm.writes;var r=receiver(f);var result=r.execute("changed-session",2000);Handler.drain();projection.put("session_id","b".repeat(64));r.sessionChanged();status(result,"stale_session");waitFor(()->f.deleted);check(context.cm.writes==before,"session invalidation closes unpublished local destination");projection.put("session_id",A);
   }
   try(var f=new FileFixture()) {
    int before=context.cm.writes;f.commit();f.block=true;var r=receiver(f);var result=r.execute("native-decision-revoked",2000);Handler.drain();waitFor(()->f.claims==1);f.nativeCurrent=false;f.claimRelease.countDown();r.sessionChanged();status(result,"stale_session");waitFor(()->f.deleted);check(context.cm.writes==before,"native decision revocation cannot be replaced by active Noise session");
   }
   try(var f=new FileFixture()) {
    f.commit();f.onReadback=()->allowed=false;var r=receiver(f);status(r.execute("revoked-in-native",2000),"stale_session");waitFor(()->f.deleted);allowed=true;
   }
   try(var f=new FileFixture()) {
    f.commit();f.onClipboard=false;var r=receiver(f);status(r.execute("clipboard-replaced",2000),"clipboard_write_unverified");waitFor(()->f.deleted);
   }
   try(var f=new FileFixture()) {
    var r=receiver(f);status(r.execute("expired",1000),"expired_or_invalid_request");waitFor(()->f.deleted);
   }
   try(var f=new FileFixture()) {
    int before=context.cm.writes;var r=receiver(f);var result=r.execute("timeout-waiting-file",1100);Handler.drain();SystemClock.now=1100;
    for(var timer:java.util.List.copyOf(Handler.delayed))timer.run();status(result,"request_timeout");waitFor(()->f.deleted);check(context.cm.writes==before,"file not committed before deadline never writes");SystemClock.now=1000;Handler.delayed.clear();
   }
   try(var f=new FileFixture()) {
    f.commit();var r=receiver(f);status(r.execute("cleanup-retry",2000),null);f.failCleanup=true;r.close();waitFor(()->!f.failCleanup);
    check(!f.deleted,"failed deletion keeps ownership for retry");for(var timer:java.util.List.copyOf(Handler.delayed))timer.run();waitFor(()->f.deleted);check(f.lease.disposed(),"periodic maintain retries exact disposable object");Handler.delayed.clear();
   }
   Handler.delayed.clear();
  }
  System.out.println("PASS "+checks+" URI receive/lease/native integration assertions; synthetic local decisions and OEM/Android doubles, no device proof");
 }
}'''


def main():
    sources = OUT / 'sources'
    classes = OUT / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    stubs = dict(rich.stubs)
    stubs[PKG+'ClipboardUriReceiverHarness.java'] = harness
    for relative, body in stubs.items():
        path = sources / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
    actual = [rich.MAIN / name for name in ['ClipboardUriReceiver.java', 'ClipboardNativeContent.java',
        'ClipboardNativeClock.java', 'ClipboardEchoGuard.java', 'ClipboardRichContent.java',
        'FileTransferEngine.java', 'FileTransferReceiveLease.java']]
    json_sources = sorted((ROOT / 'build/file-transfer-validation/json-src/org/json').glob('*.java'))
    assert len(json_sources) >= 6
    rich.run('compile', rich.JDK / 'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-d', classes,
        *actual, *json_sources, *(sources / path for path in sorted(stubs)))
    rich.run('uri-receive-tests', rich.JDK / 'java.exe', '-cp', classes, 'io.github.yuejie.connect.ClipboardUriReceiverHarness')
    (OUT / 'evidence.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-uri-receiver.py',
        source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in [*actual, Path(__file__), ROOT / 'scripts/validate-clipboard-rich.py']},
        generated_test_sha256={str(p.relative_to(OUT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (sources / path for path in sorted(stubs))},
        result='passed', apk=None, device_operations=False,
        scope='actual local engine final-commit future and lease handoff into URI native clipboard receiver; OEM/platform doubles'), indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
