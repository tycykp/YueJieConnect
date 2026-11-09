"""Synthetic HTML probe checks in OEM/Android doubles, never a device acceptance claim."""
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('probe_tests', ROOT / 'scripts/validate-clipboard-probe.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
OUT = ROOT / 'build/clipboard-validation/html-probe-host'
probe.OUT = OUT
harness = r'''package io.github.yuejie.connect;
import android.content.*;import android.os.*;import android.util.Log;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public class ClipboardHtmlProbeHarness {
 static final String RUN="b".repeat(32),SESSION="a".repeat(64),PRIVATE="PRIVATE synthetic snapshot";
 static int checks;
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
 static String text(int leg){return "YJ_HTML_"+RUN+"_"+leg+"_中文🙂\n\t";}
 static String html(int leg){return "<b>"+text(leg)+"</b><i>synthetic</i>";}
 static void command(Context c,String op,int leg){Intent i=new Intent("io.github.yuejie.connect.CLIPBOARD_NATIVE_PROBE");
  i.args.put("run",RUN);i.args.put("op",op);i.args.put("leg",leg);i.args.put("format","html");c.receiver.onReceive(c,i);Handler.drain();}
 static boolean event(String value){return Log.events.stream().anyMatch(e->e.contains("result="+value+"}"));}
 static void observe(){Handler.delayed.remove(Handler.delayed.size()-1).run();Handler.drain();}
 public static void main(String[] args)throws Exception {
  for(int brand:new int[]{1,2}){
   Handler.delayed.clear();Handler.immediate.clear();Log.events.clear();
   Context c=new Context();c.app=new android.app.Application();c.clipboard.clip=ClipData.newPlainText("original",PRIVATE);V3.V.sink=c.clipboard;
   BridgeClient client=new BridgeClient();client.value=new org.json.JSONObject().put("id","peer").put("session_id",SESSION);
   AtomicReference<CompletableFuture<ClipboardBridge.RichReceipt>> future=new AtomicReference<>();
   ClipboardBridge.Probe p=ClipboardNativeProbe.install(c,new io.github.libxposed.api.XposedModule(),ClipboardHtmlProbeHarness.class.getClassLoader(),client,brand,()->null,future::get);
   command(c,"prepare",0);check(event("peer_unavailable"),"plain authorization cannot arm HTML");check(c.clipboard.writes==0,"no historical copy");
   client.value.put("operations",new org.json.JSONArray().put("clipboard_rich_write"));
   command(c,"prepare",0);check(event("armed_html")&&!event("armed"),"explicit rich operation and format-specific handshake required");
   check(!p.permitOutbound(PRIVATE)&&!p.permitInbound(PRIVATE),"armed private content blocked both ways");
   command(c,"copy",1);check(event("platform_copy"),"genuine platform copy");
   check(html(1).equals(c.clipboard.clip.getItemAt(0).getHtmlText()),"exact explicit HTML stimulus");
   check(!event("remote_ack")&&future.get()==null,"probe never fabricates bridge call or ack");
   CompletableFuture<ClipboardBridge.RichReceipt> actual=new CompletableFuture<>();future.set(actual);observe();
   int bytes=OplusClipboardCodec.encode(text(1)).length+OplusClipboardCodec.encode(html(1)).length;
   actual.complete(new ClipboardBridge.RichReceipt("wrong-peer",SESSION,"request",bytes));Handler.drain();
   check(event("failed")&&!event("remote_ack"),"wrong-peer receipt rejected");
   Log.events.clear();command(c,"copy",2);actual=new CompletableFuture<>();future.set(actual);observe();
   actual.complete(new ClipboardBridge.RichReceipt("peer",SESSION,"request2",OplusClipboardCodec.encode(text(2)).length));Handler.drain();
   check(event("failed")&&!event("remote_ack"),"plain-only byte count rejected");
   Log.events.clear();command(c,"copy",1);actual=new CompletableFuture<>();future.set(actual);observe();
   actual.complete(new ClipboardBridge.RichReceipt("peer",SESSION,"request3",bytes));Handler.drain();check(event("remote_ack"),"only actual bound rich future accepted");
   command(c,"check",1);check(event("matches"),"exact two-part readback");
   c.clipboard.clip=ClipData.newHtmlText("test",text(1),"<i>lossy</i>");Log.events.clear();command(c,"check",1);
   check(event("unverified")&&!event("matches"),"same plain text with different HTML is not verified");
   c.clipboard.clip=ClipData.newHtmlText("test",text(1),html(1));command(c,"finish",0);
   check(event("restored")&&PRIVATE.contentEquals(c.clipboard.clip.getItemAt(0).getText()),"OEM restoration preserves original");
   check(!p.permitOutbound(PRIVATE)&&!p.permitInbound(text(1)),"late source and incoming guard");
   check(Log.events.stream().noneMatch(s->s.contains(PRIVATE)||s.contains("<b>")),"no original or HTML payload logged");p.close();
  }
  System.out.println("PASS "+checks+" HTML probe assertions; Android/OEM doubles, no device proof");
 }
}'''


def main():
    stubs = dict(probe.stubs)
    stubs['org/json/JSONArray.java'] = '''package org.json;public class JSONArray {
      java.util.List<String> values=new java.util.ArrayList<>();public JSONArray put(String v){values.add(v);return this;}
      public int length(){return values.size();}public String optString(int i){return values.get(i);}}'''
    stubs['org/json/JSONObject.java'] = stubs['org/json/JSONObject.java'].replace(
        'return new JSONArray();', 'return (JSONArray)data.getOrDefault(k,new JSONArray().put("clipboard_write"));')
    stubs[probe.PKG + '/ClipboardHtmlProbeHarness.java'] = harness
    sources, classes = OUT / 'sources', OUT / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for relative, body in stubs.items():
        path = sources / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
    probe.run('compile', probe.JDK / 'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-d', classes,
              probe.PROBE, probe.CODEC, probe.NATIVE_CLOCK, *sorted(sources.rglob('*.java')))
    probe.run('html-probe-tests', probe.JDK / 'java.exe', '-cp', classes, 'io.github.yuejie.connect.ClipboardHtmlProbeHarness')
    files = [probe.PROBE, probe.CODEC, probe.NATIVE_CLOCK, Path(__file__), Path(probe.__file__)]
    (OUT / 'evidence.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-html-probe.py',
        result='passed', source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
        apk=None, device_operations=False), indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
