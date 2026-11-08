"""Tests only the clipboard/file ownership boundary, not FileTransferEngine's existing tests.
Uses the actual FileTransferSource for binary snapshots/cleanup and a controllable transport
receipt double. No device, network, native clipboard write, or file capability publication.
"""
import hashlib
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/clipboard-validation/uri-host'
MAIN = ROOT / 'android/module/src/main/java/io/github/qiangjian/connect'
JDK = Path(os.environ.get('CLIPBOARD_JDK', r'C:\Program Files\Android\Android Studio\jbr')) / 'bin'
PKG = 'io/github/qiangjian/connect'
stubs = {
'android/os/Looper.java': '''package android.os;
public class Looper {static final Looper main=new Looper();public static boolean onMain;
public static Looper getMainLooper(){return main;}public static Looper myLooper(){return onMain?main:null;}}''',
'android/net/Uri.java': '''package android.net;
public class Uri {final String value;private Uri(String v){value=v;}public static Uri parse(String v){return new Uri(v);}
public String getScheme(){return value.substring(0,value.indexOf(':'));}public String getAuthority(){return value.contains("://")?value.substring(value.indexOf("://")+3).split("/")[0]:null;}
public String toString(){return value;}}''',
'android/content/Context.java': '''package android.content;
public class Context {public String name="com.oplus.linker";public String getPackageName(){return name;}}''',
'android/content/ClipData.java': '''package android.content;
public class ClipData {public static class Item {final CharSequence text;final String html;final android.net.Uri uri;final Object intent;
public Item(CharSequence t,String h,android.net.Uri u,Object i){text=t;html=h;uri=u;intent=i;}
public CharSequence getText(){return text;}public String getHtmlText(){return html;}public android.net.Uri getUri(){return uri;}public Object getIntent(){return intent;}}
final java.util.List<Item> items=new java.util.ArrayList<>();public ClipData(Item i){items.add(i);}public int getItemCount(){return items.size();}public Item getItemAt(int i){return items.get(i);}public void add(Item i){items.add(i);}}
''',
PKG+'/FileTransferUris.java': '''package io.github.qiangjian.connect;
public class FileTransferUris {static java.io.File root;static byte[] bytes={0,1,2,(byte)255};static int reads;static boolean deny;
public static FileTransferSource snapshot(android.content.Context c,android.net.Uri uri,String name,String mime,long size)throws java.io.IOException {
 reads++;if(deny)throw new SecurityException("private content://secret must not escape");
 return FileTransferSource.snapshot(root,new java.io.ByteArrayInputStream(bytes),name,mime,size);
}}''',
PKG+'/FileTransferClient.java': '''package io.github.qiangjian.connect;
public class FileTransferClient {public int sends;public FileTransferEngine.Key key;public FileTransferSource source;
public java.util.concurrent.CompletableFuture<FileTransferEngine.Receipt> result=new java.util.concurrent.CompletableFuture<>();
public boolean throwBeforeOwnership;
public java.util.concurrent.CompletableFuture<FileTransferEngine.Receipt> send(FileTransferEngine.Key k,FileTransferSource s){
 sends++;if(throwBeforeOwnership)throw new IllegalStateException("transport unavailable");key=k;source=s;
 result.whenComplete((v,e)->{try{s.close();}catch(java.io.IOException failure){throw new RuntimeException(failure);}});return result;
}}
''',
}
harness = r'''package io.github.qiangjian.connect;
import android.content.*;import android.net.Uri;
import java.io.*;import java.nio.file.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public class ClipboardUriHarness {
 static final FileTransferEngine.Key KEY=FileTransferEngine.Key.create("peer","a".repeat(64));
 static final AtomicBoolean live=new AtomicBoolean(true);static int checks;
 static final Context context=new Context();
 static ClipData clip(){return new ClipData(new ClipData.Item(null,null,Uri.parse("content://synthetic/item"),null));}
 static ClipboardUriTransfer prepare(ClipData clip)throws Exception{return ClipboardUriTransfer.prepare(context,clip,KEY,k->live.get(),"synthetic.bin","application/octet-stream",-1);}
 static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);checks++;}
 interface Call {void run()throws Exception;}
 static void rejects(Call call,String code)throws Exception {try{call.run();throw new AssertionError("accepted "+code);}catch(IOException|IllegalArgumentException|IllegalStateException|SecurityException e){check(code.equals(e.getMessage()),"error "+code);}}
 static void failed(CompletableFuture<?> f,String code){try{f.join();throw new AssertionError("accepted receipt");}catch(CompletionException e){check(code.equals(e.getCause().getMessage()),"future error "+code);}}
 static long files()throws Exception{try(var files=Files.list(FileTransferUris.root.toPath())){return files.count();}}
 public static void main(String[] args)throws Exception {
  FileTransferUris.root=Files.createTempDirectory("qj-uri-test-").toFile();
  try {
   android.os.Looper.onMain=true;rejects(()->prepare(clip()),"clipboard_uri_requires_worker");android.os.Looper.onMain=false;
   check(FileTransferUris.reads==0,"No I/O on main thread");
   context.name="io.github.qiangjian.connect";rejects(()->prepare(clip()),"invalid_executor_context");context.name="com.oplus.linker";
   live.set(false);rejects(()->prepare(clip()),"stale_session");live.set(true);
   rejects(()->prepare(new ClipData(new ClipData.Item("caption",null,Uri.parse("content://synthetic/item"),null))),"unsupported_clipboard_format");
   rejects(()->prepare(new ClipData(new ClipData.Item(null,"<b>html</b>",Uri.parse("content://synthetic/item"),null))),"unsupported_clipboard_format");
   rejects(()->prepare(new ClipData(new ClipData.Item(null,null,Uri.parse("content://synthetic/item"),new Object()))),"unsupported_clipboard_format");
   rejects(()->prepare(new ClipData(new ClipData.Item(null,null,Uri.parse("file:///private/path"),null))),"unsupported_clipboard_format");
   ClipData multi=clip();multi.add(clip().getItemAt(0));rejects(()->prepare(multi),"unsupported_clipboard_format");
   check(FileTransferUris.reads==0,"Unsupported content cannot reach provider");
   FileTransferUris.deny=true;rejects(()->prepare(clip()),"permission_denied");FileTransferUris.deny=false;
   ClipboardUriTransfer prepared=prepare(clip());
   check(files()==1&&prepared.offer().size()==4,"Actual private binary snapshot");
   check(prepared.offer().sha256().equals(FileTransferEngine.hex(FileTransferEngine.sha256().digest(FileTransferUris.bytes))),"Actual binary checksum");
   check(!prepared.offer().toString().contains("content://"),"Wire metadata contains no original URI");
   FileTransferUris.bytes[0]=9;
   FileTransferClient client=new FileTransferClient();var upload=prepared.sendAfterReceiverConsent(client);
   try(InputStream in=client.source.open()){check(in.read()==0,"Snapshot remains immutable");}
   check(client.sends==1&&!upload.isDone(),"No clipboard/file success without remote receipt");
   failed(prepared.sendAfterReceiverConsent(client),"clipboard_uri_source_consumed");
   client.result.complete(new FileTransferEngine.Receipt(KEY,prepared.offer().size(),prepared.offer().sha256()));
   check(upload.join().key().equals(KEY)&&upload.join().offer().equals(prepared.offer()),"Bound measured file receipt accepted");
   prepared.close();check(files()==0,"Owned upload snapshot released");
   prepared=prepare(clip());client=new FileTransferClient();upload=prepared.sendAfterReceiverConsent(client);
   client.result.complete(new FileTransferEngine.Receipt(FileTransferEngine.Key.create("other","a".repeat(64)),prepared.offer().size(),prepared.offer().sha256()));
   failed(upload,"invalid_file_receipt");prepared.close();
   prepared=prepare(clip());client=new FileTransferClient();upload=prepared.sendAfterReceiverConsent(client);
   client.result.completeExceptionally(new IOException("receive_consent_required"));failed(upload,"receive_consent_required");prepared.close();
   prepared=prepare(clip());client=new FileTransferClient();upload=prepared.sendAfterReceiverConsent(client);
   client.result.completeExceptionally(new IOException("content://private/secret"));failed(upload,"clipboard_uri_upload_failed");prepared.close();
   prepared=prepare(clip());live.set(false);client=new FileTransferClient();failed(prepared.sendAfterReceiverConsent(client),"stale_session");
   check(client.sends==0&&files()==0,"Revocation before send closes local snapshot without upload");live.set(true);
   prepared=prepare(clip());client=new FileTransferClient();upload=prepared.sendAfterReceiverConsent(client);live.set(false);
   client.result.complete(new FileTransferEngine.Receipt(KEY,prepared.offer().size(),prepared.offer().sha256()));failed(upload,"stale_session");live.set(true);prepared.close();
   prepared=prepare(clip());client=new FileTransferClient();upload=prepared.sendAfterReceiverConsent(client);upload.cancel(false);
   check(client.result.isCancelled(),"Caller cancellation reaches file client");prepared.close();check(files()==0,"Cancelled source cleaned");
   prepared=prepare(clip());client=new FileTransferClient();upload=prepared.sendAfterReceiverConsent(client);prepared.close();
   check(upload.isCancelled()&&client.result.isCancelled()&&files()==0,"Close cancels and releases ownership");
   prepared=prepare(clip());client=new FileTransferClient();client.throwBeforeOwnership=true;failed(prepared.sendAfterReceiverConsent(client),"clipboard_uri_send_failed");
   prepared.close();check(files()==0,"Synchronous transport rejection cleanup");
   prepared=prepare(clip());prepared.close();prepared.close();check(files()==0,"Unsent close is idempotent");
   System.out.println("PASS "+checks+" URI boundary assertions; actual snapshot files, URI/transport doubles; not device proof");
  }finally{try(var stream=Files.list(FileTransferUris.root.toPath())){for(Path p:stream.toList())Files.delete(p);}Files.delete(FileTransferUris.root.toPath());}
 }
}'''


def main():
    sources = OUT / 'sources'
    classes = OUT / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    stubs[PKG + '/ClipboardUriHarness.java'] = harness
    for name, body in stubs.items():
        target = sources / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body, encoding='utf-8')
    actual = [MAIN / name for name in ['ClipboardUriTransfer.java', 'FileTransferSource.java', 'FileTransferEngine.java']]
    for name, cmd in [
        ('compile', [JDK / 'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-d', classes, *actual, *sources.rglob('*.java')]),
        ('uri-tests', [JDK / 'java.exe', '-cp', classes, 'io.github.qiangjian.connect.ClipboardUriHarness'])]:
        result = subprocess.run([str(a) for a in cmd], cwd=ROOT, capture_output=True)
        output = result.stdout.decode('utf-8', 'replace') + result.stderr.decode('utf-8', 'replace')
        (OUT / (name + '.txt')).write_text(output, encoding='utf-8')
        if result.returncode:
            raise RuntimeError(name + ' failed: ' + str(OUT / (name + '.txt')))
        print(output.strip() or name + ': PASS')
    tested = [*actual, Path(__file__)]
    (OUT / 'evidence.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-uri.py',
        result='passed', source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in tested},
        apk=None, device_operations=False, scope='URI upload ownership boundary only; not native URI clipboard delivery'), indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
