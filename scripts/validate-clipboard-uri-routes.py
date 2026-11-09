"""Test only new clipboard URI routing/ACK boundaries with real engine and receive leases."""
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('uri_receiver_tests', ROOT / 'scripts/validate-clipboard-uri-receiver.py')
receive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(receive)
rich = receive.rich
OUT = ROOT / 'build/clipboard-validation/uri-routes-host'
rich.OUT = OUT
harness = r'''package io.github.yuejie.connect;
import android.os.*;import java.util.*;import java.util.concurrent.*;import org.json.*;
public class ClipboardUriRoutesHarness extends ClipboardUriReceiverHarness {
 static ClipboardUriRoutes routes(){return new ClipboardUriRoutes(endpoint,echoes,()->allowed,()->projection);}
 static ClipboardUriCodec.Request payload(FileFixture f){return new ClipboardUriCodec.Request(f.key.transfer(),f.offer);}
 static CompletableFuture<ClipboardUriReceiver.Result> execute(ClipboardUriRoutes r,FileFixture f,String id){return r.execute("peer",A,id,payload(f),2000);}
 interface Action{void run()throws Exception;}
 static void reject(Action action)throws Exception{try{action.run();throw new AssertionError("accepted invalid payload/result");}catch(IllegalArgumentException expected){checks++;}}
 public static void main(String[] args)throws Exception {
  for(int brand:new int[]{1,2}){
   reset(brand);
   try(var f=new FileFixture()){
    var r=routes();status(execute(r,f,"not-selected"),"receive_consent_required");check(context.cm.writes==0,"wire cannot create or authorize lease");
    var wire=ClipboardUriCodec.encode(payload(f));check(ClipboardUriCodec.decode(wire).equals(payload(f)),"strict metadata round trip");
    reject(()->ClipboardUriCodec.decode(new JSONObject(wire.toString()).put("uri","content://synthetic")));
    reject(()->ClipboardUriCodec.decode(new JSONObject(wire.toString()).put("size",4.0)));
    reject(()->ClipboardUriCodec.decode(new JSONObject(wire.toString()).put("mime",JSONObject.NULL)));
    check(r.attach(f.lease),"actual accepted native lease transfers cleanup ownership");check(!r.attach(f.lease),"same lease cannot attach twice");
    var bad=new ClipboardUriCodec.Request(f.key.transfer(),new FileTransferEngine.Offer("changed.bin",f.offer.mime(),f.offer.size(),f.offer.sha256()));
    status(r.execute("peer",A,"bad-offer",bad,2000),"consent_metadata_mismatch");
    status(r.execute("other-peer",A,"other-peer",payload(f),2000),"stale_session");
    var pending=execute(r,f,"pending");Handler.drain();check(!pending.isDone(),"commit remains mandatory");
    // A second request ID for the same transfer cannot cancel the first request by aliasing its receiver.
    status(execute(r,f,"second"),"clipboard_uri_already_started");check(!r.cancel("peer",A,"second").join(),"rejected request has no cancellation authority");
    status(r.execute("peer",A,"pending",bad,2000),"request_id_reused");
    var caller=execute(r,f,"pending");caller.complete(new ClipboardUriReceiver.Result("peer",A,"pending",f.key.transfer(),0,null,"forged"));
    check(!pending.isDone(),"caller completion cannot change route ledger");
    f.commit();status(pending,null);check(context.cm.writes==1,"one real URI import");
    var ack=ClipboardUriCodec.applied(f.key,"pending",f.offer,pending.join());check(ClipboardUriCodec.matchesAck(ack,f.key,f.offer),"ACK binds exact applied transfer");
    check(!ack.toString().contains("content://")&&!ack.toString().contains("synthetic.bin"),"ACK contains no local URI or name");
    check(!ClipboardUriCodec.matchesAck(new JSONObject(ack.toString()).put("size",4.0),f.key,f.offer),"fractional ACK rejected");
    check(!ClipboardUriCodec.matchesAck(new JSONObject(ack.toString()).put("applied","true"),f.key,f.offer),"string applied flag rejected");
    check(!ClipboardUriCodec.matchesAck(new JSONObject(ack.toString()).put("uri","content://synthetic"),f.key,f.offer),"extra URI rejected");
    reject(()->ClipboardUriCodec.applied(f.key,"wrong-request",f.offer,pending.join()));
    reject(()->ClipboardUriCodec.applied(f.key,"pending",f.offer,new ClipboardUriReceiver.Result("peer",A,"pending",f.key.transfer(),0,null,"failed")));
    status(execute(r,f,"pending"),null);check(context.cm.writes==1,"same request returns actual receipt without reimport");
    var cancel=r.cancel("peer",A,"pending");Handler.drain();check(!cancel.join(),"successful clipboard never claims rollback");
    r.close();waitFor(()->f.deleted);check(f.lease.disposed(),"route close deletes only owned local destination");
   }
   try(var f=new FileFixture()){
    var r=routes();check(r.attach(f.lease),"attach cancellation fixture");var pending=execute(r,f,"cancel");Handler.drain();
    check(!r.cancel("wrong-peer",A,"cancel").join(),"wrong peer cannot cancel");var cancel=r.cancel("peer",A,"cancel");Handler.drain();
    check(cancel.join(),"cancellation before commit wins");status(pending,"cancelled");waitFor(()->f.deleted);r.close();
   }
   try(var f=new FileFixture()){
    var r=routes();check(r.attach(f.lease),"attach revocation fixture");var pending=execute(r,f,"revoke");Handler.drain();
    allowed=false;r.sessionChanged();status(pending,"stale_session");waitFor(()->f.deleted);r.close();allowed=true;
   }
   // Capacity includes successful paste files and cleanup ownership, not just in-flight network work.
   java.util.List<FileFixture> all=new java.util.ArrayList<>();var r=routes();
   try{
    for(int i=0;i<5;i++)all.add(new FileFixture());
    for(int i=0;i<4;i++)check(r.attach(all.get(i).lease),"bounded local attachment "+i);
    check(!r.attach(all.get(4).lease)&&!all.get(4).deleted,"capacity rejection leaves caller ownership");
    r.close();waitFor(()->all.subList(0,4).stream().allMatch(f->f.deleted));
    check(!all.get(4).deleted,"close does not delete unowned rejected lease");
   }finally{for(var f:all)f.close();}
   endpoint.close();Handler.delayed.clear();
  }
  System.out.println("PASS "+checks+" URI route/strict ACK assertions; actual engine/lease with Android/OEM doubles, no device proof");
 }
}'''


def main():
    sources, classes = OUT / 'sources', OUT / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    stubs = dict(rich.stubs)
    stubs[rich.PKG + 'ClipboardUriReceiverHarness.java'] = receive.harness
    stubs[rich.PKG + 'ClipboardUriRoutesHarness.java'] = harness
    for relative, body in stubs.items():
        path = sources / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
    actual = [rich.MAIN / name for name in ['ClipboardUriRoutes.java', 'ClipboardUriCodec.java', 'ClipboardUriReceiver.java',
        'ClipboardNativeContent.java', 'ClipboardNativeClock.java', 'ClipboardEchoGuard.java', 'ClipboardRichContent.java',
        'FileTransferEngine.java', 'FileTransferReceiveLease.java']]
    json_sources = sorted((ROOT / 'build/file-transfer-validation/json-src/org/json').glob('*.java'))
    assert len(json_sources) >= 6
    files = [*actual, Path(__file__), Path(receive.__file__), Path(rich.__file__)]
    inputs = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    rich.run('compile', rich.JDK / 'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-d', classes,
        *actual, *json_sources, *sorted(sources.rglob('*.java')))
    rich.run('uri-routes-tests', rich.JDK / 'java.exe', '-cp', classes, 'io.github.yuejie.connect.ClipboardUriRoutesHarness')
    races = [p for p, digest in inputs.items() if hashlib.sha256((ROOT / p).read_bytes()).hexdigest() != digest]
    (OUT / 'evidence.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-uri-routes.py',
        result='passed' if not races else 'source_changed', apk=None, device_operations=False,
        source_sha256=inputs, source_races=races,
        generated_test_sha256={str(p.relative_to(OUT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources.rglob('*.java')}), indent=2), encoding='utf-8')
    if races:
        raise RuntimeError('Dependency changed during test; evidence is not a current-source pass')


if __name__ == '__main__':
    main()
