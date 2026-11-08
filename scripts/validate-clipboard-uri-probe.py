"""Only the debug URI fixture/privacy/observation path; no device or native consumer claim."""
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('probe', ROOT / 'scripts/validate-clipboard-probe.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
OUT = ROOT / 'build/clipboard-validation/uri-probe-host'
probe.OUT = OUT
stubs = dict(probe.stubs)
stubs['org/json/JSONArray.java'] = 'package org.json;public class JSONArray{public int length(){return 2;}public String optString(int i){return i==0?"clipboard_uri_write":"clipboard_write";}}'
stubs['androidx/core/content/FileProvider.java'] = '''package androidx.core.content;public class FileProvider{
 public static java.util.Map<android.net.Uri,java.io.File> files=new java.util.HashMap<>();
 public static android.net.Uri getUriForFile(android.content.Context c,String authority,java.io.File file,String name){var u=android.net.Uri.parse("content://"+authority+"/"+file.getName());files.put(u,file);return u;}}'''
harness = r'''package io.github.qiangjian.connect;
import android.content.*;import android.net.*;import android.os.*;import android.util.Log;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.util.function.*;
public class ClipboardUriProbeHarness{
 static int checks;static final String RUN="b".repeat(32),SESSION="a".repeat(64),PRIVATE="PRIVATE test snapshot";
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;}
 static void waitFor(BooleanSupplier b)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!b.getAsBoolean()){Handler.drain();if(System.nanoTime()>end)throw new AssertionError("wait");Thread.sleep(2);}Handler.drain();}
 static void command(Context c,String op,int leg){Intent i=new Intent("io.github.qiangjian.connect.CLIPBOARD_NATIVE_PROBE");i.args.put("run",RUN);i.args.put("op",op);i.args.put("leg",leg);i.args.put("format","uri");c.receiver.onReceive(c,i);Handler.drain();}
 static boolean event(String result){return Log.events.stream().anyMatch(s->s.contains("result="+result+"}"));}
 static String name(int leg){return "QJ_URI_"+RUN+"_"+leg+".bin";}
 static String hash(byte[] b)throws Exception{var out=new StringBuilder();for(byte x:java.security.MessageDigest.getInstance("SHA-256").digest(b))out.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return out.toString();}
 static void observer(){Handler.delayed.remove(Handler.delayed.size()-1).run();Handler.drain();}
 public static void main(String[] args)throws Exception{
  for(int brand:new int[]{1,2}){
   Handler.delayed.clear();Handler.immediate.clear();Log.events.clear();Context c=new Context();c.app=new android.app.Application();c.packageName=brand==1?"com.xiaomi.mirror":"com.oplus.linker";
   ClipData original=new ClipData(new ClipData.Item(PRIVATE,"<b>private</b>","content://private",new Intent("private")));c.clipboard.clip=original;V3.V.sink=c.clipboard;
   BridgeClient client=new BridgeClient();client.value=new org.json.JSONObject().put("id","peer").put("session_id",SESSION);
   ClipboardBridge.Probe p=ClipboardNativeProbe.install(c,new io.github.libxposed.api.XposedModule(),ClipboardUriProbeHarness.class.getClassLoader(),client,brand,()->null,()->null);
   command(c,"prepare",0);check(event("uri_probe_unavailable")&&c.clipboard.writes==0,"URI bridge observer must actually attach before preparation");
   AtomicReference<CompletableFuture<ClipboardUriBridge.Receipt>> future=new AtomicReference<>();p.attachUriTransfers(future::get);Log.events.clear();command(c,"prepare",0);
   check(c.actions.contains("io.github.qiangjian.connect.CLIPBOARD_URI_NATIVE_PROBE"),"URI action attaches only when a real URI bridge observer exists");
   check(event("armed_uri"),"URI mode handshake");check(!p.permitOutbound(PRIVATE)&&!p.permitInbound("text"),"plain/HTML privacy guard also applies to URI tests");
   check(!p.permitUriOutbound(original),"original URI cannot leave native process");check(!p.permitUriInbound("private.bin",8193,"a".repeat(64)),"unrelated incoming cannot overwrite snapshot");
   command(c,"copy",1);waitFor(()->event("platform_copy"));Uri local=c.clipboard.clip.getItemAt(0).getUri();var file=androidx.core.content.FileProvider.files.get(local);byte[] bytes=Files.readAllBytes(file.toPath());
   check(bytes.length==8193&&new String(bytes,0,name(1).length(),java.nio.charset.StandardCharsets.UTF_8).equals(name(1)),"cross-chunk file is genuinely synthetic and bound to run/direction");
   check(p.permitUriOutbound(c.clipboard.clip)&&!p.permitUriOutbound(original),"only generated source handle can transmit while armed");
   check(p.permitUriInbound(name(1),8193,hash(bytes))&&!p.permitUriInbound(name(1),8192,hash(bytes))&&!p.permitUriInbound(name(1),8193,"f".repeat(64)),"URI inbound guard binds real size/SHA");
   check(future.get()==null&&!event("remote_ack"),"fixture does not fabricate any transport transfer or receipt");
   var actual=new CompletableFuture<ClipboardUriBridge.Receipt>();future.set(actual);observer();actual.complete(new ClipboardUriBridge.Receipt("wrong",SESSION,"request","d".repeat(32),8193,hash(bytes)));Handler.drain();
   check(event("failed")&&!event("remote_ack"),"wrong peer receipt rejected");
   Log.events.clear();command(c,"copy",2);waitFor(()->event("platform_copy"));var second=androidx.core.content.FileProvider.files.get(c.clipboard.clip.getItemAt(0).getUri());byte[] secondBytes=Files.readAllBytes(second.toPath());
   actual=new CompletableFuture<>();future.set(actual);observer();actual.complete(new ClipboardUriBridge.Receipt("peer",SESSION,"request2","e".repeat(32),8193,hash(secondBytes)));Handler.drain();check(event("remote_ack"),"actual bound URI result observed");
   ClipData received=new ClipData("synthetic",new String[]{"application/octet-stream"},new ClipData.Item(Uri.parse("content://synthetic-recipient/received")));
   c.clipboard.clip=received;p.uriStaged(SESSION,name(1),received);Log.events.clear();command(c,"check",1);check(event("unverified"),"placeholder stage alone never matches completed business");
   p.uriApplied(SESSION,name(1),received);Log.events.clear();command(c,"check",1);check(event("unverified"),"OEM import without actual native provider consumer is insufficient");
   p.uriConsumed(SESSION,name(1));Log.events.clear();command(c,"check",1);check(event("matches"),"real readback plus native provider execution required");
   command(c,"finish",0);waitFor(()->event("deleted"));check(event("restored")&&!file.exists()&&!second.exists(),"restore and exact synthetic source files cleaned");
   check(PRIVATE.contentEquals(c.clipboard.clip.getItemAt(0).getText())&&!p.permitUriOutbound(original),"arbitrary original rich/URI item restored without resending");
   check(!p.permitUriInbound(name(1),8193,hash(bytes)),"late test file offer rejected after restore");
   check(p.permitUriOutbound(new ClipData("user",new String[]{"application/octet-stream"},new ClipData.Item(Uri.parse("content://user/new"))))&&p.permitUriOutbound(original),"fresh real URI copy releases restoration guard");
   Log.events.clear();command(c,"prepare",0);command(c,"copy",1);waitFor(()->event("platform_copy"));var retained=androidx.core.content.FileProvider.files.get(c.clipboard.clip.getItemAt(0).getUri());c.clipboard.failWrite=true;
   command(c,"finish",0);check(event("failed_retry_pending")&&retained.exists(),"failed native restore retains snapshot and synthetic source ownership");c.clipboard.failWrite=false;command(c,"finish",0);waitFor(()->event("deleted"));check(!retained.exists(),"safe restore retry releases exact fixture");
   check(Log.events.stream().noneMatch(s->s.contains(PRIVATE)||s.contains("content://private")||s.contains("<b>")),"no private content in URI probe evidence");p.close();
   Log.events.clear();p=ClipboardNativeProbe.install(c,new io.github.libxposed.api.XposedModule(),ClipboardUriProbeHarness.class.getClassLoader(),client,brand,()->null,()->null);
   Intent guard=new Intent("io.github.qiangjian.connect.CLIPBOARD_NATIVE_PROBE");guard.args.put("run",RUN);guard.args.put("op","prepare");guard.args.put("format","uri_guard");c.receiver.onReceive(c,guard);Handler.drain();
   check(event("armed_uri_guard")&&!p.permitOutbound(PRIVATE)&&!p.permitInbound("other"),"separate-process plain/HTML guard requires no URI executor and blocks private events");
   int before=c.clipboard.writes;command(c,"copy",1);check(event("guard_only")&&c.clipboard.writes==before,"cross-process privacy guard cannot generate a copy");
   command(c,"finish",0);check(event("unchanged")&&!p.permitOutbound(PRIVATE),"guard snapshots current original even when it did not perform the native restoration");p.close();
   p=ClipboardNativeProbe.installUri(c,new io.github.libxposed.api.XposedModule(),ClipboardUriProbeHarness.class.getClassLoader(),client,brand);
   check(c.actions.equals(java.util.List.of("io.github.qiangjian.connect.CLIPBOARD_URI_NATIVE_PROBE")),"default provider process does not receive the app-process plain/HTML probe action");p.close();
  }
  System.out.println("PASS "+checks+" URI-probe privacy/fixture/real-future assertions; Android/OEM doubles, no device proof");
 }
}'''


def main():
    sources, classes = OUT / 'sources', OUT / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    stubs[probe.PKG+'/ClipboardUriProbeHarness.java'] = harness
    for relative, body in stubs.items():
        path = sources / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
    actual = [probe.PROBE, probe.CODEC, probe.NATIVE_CLOCK]
    probe.run('compile',probe.JDK/'javac.exe','--release','17','-encoding','UTF-8','-d',classes,*actual,*sorted(sources.rglob('*.java')))
    probe.run('uri-probe-tests',probe.JDK/'java.exe','-cp',classes,'io.github.qiangjian.connect.ClipboardUriProbeHarness')
    files = [*actual,Path(__file__),Path(probe.__file__)]
    (OUT/'evidence.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-uri-probe.py',result='passed',
        source_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files},apk=None,device_operations=False),indent=2),encoding='utf-8')


if __name__ == '__main__':
    main()
