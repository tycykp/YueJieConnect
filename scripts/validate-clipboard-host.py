"""Clipboard-only host validation. No Gradle, APK installation, or device access.

Receiver scheduling tests compile the actual receivers against tiny deterministic Android/OEM
test doubles; they verify ordering, never claim a real OEM sink or end-to-end device result.
"""
import hashlib
import argparse
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/clipboard-validation'
MAIN = ROOT / 'android/module/src/main/java/io/github/yuejie/connect'
TEST = ROOT / 'android/module/src/test/java/io/github/yuejie/connect/ClipboardWireCodecTest.java'
JDK = Path(os.environ.get('CLIPBOARD_JDK', r'C:\Program Files\Android\Android Studio\jbr')) / 'bin'
CACHE = Path.home() / '.gradle/caches'
ANDROID = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/platforms/android-37.0/android.jar'
OUT.mkdir(parents=True, exist_ok=True)
parser = argparse.ArgumentParser()
parser.add_argument('--compile-only', action='store_true', help='Compile changed integration without rerunning unchanged host tests')
args = parser.parse_args()

def run(name, *args):
    result = subprocess.run([str(a) for a in args], cwd=ROOT, capture_output=True)
    output = result.stdout.decode('utf-8', 'replace') + result.stderr.decode('utf-8', 'replace')
    (OUT / (name + '.txt')).write_text(output, encoding='utf-8')
    if result.returncode:
        raise RuntimeError(f'{name} failed; see {OUT / (name + ".txt")}')
    print(name + ': PASS')

classes = OUT / 'classes'
classes.mkdir(exist_ok=True)
api = next(CACHE.glob('9.7.1/transforms/*/transformed/api-102.0.0-api.jar'))
sources = sorted(p for p in MAIN.glob('*Clipboard*.java') if p.name != 'OplusClipboardSelfTest.java')
sources += sorted((ROOT / 'android/module/src/debug/java/io/github/yuejie/connect').glob('*Clipboard*.java'))
dependencies = [MAIN / 'BridgeClient.java', MAIN / 'IBridge.java']
if (MAIN / 'NativeLogicalSessions.java').exists():
    dependencies.append(MAIN / 'NativeLogicalSessions.java')
if (MAIN / 'ClipboardUriTransfer.java').exists():
    dependencies.extend(sorted(MAIN.glob('FileTransfer*.java')))
input_sha256 = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sources, *dependencies]}
run('compile', JDK / 'javac.exe', '-encoding', 'UTF-8', '--release', '17', '-cp',
    os.pathsep.join(map(str, [ANDROID, api])), '-d', classes, *sources, *dependencies)
if args.compile_only:
    races = [path for path, digest in input_sha256.items() if hashlib.sha256((ROOT / path).read_bytes()).hexdigest() != digest]
    (OUT / 'compile-validation.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-host.py --compile-only',
        source_sha256=input_sha256, source_races=races, apk=None, device_operations=False), indent=2), encoding='utf-8')
    if races:
        raise RuntimeError('Inputs changed during compile; see compile-validation.json; do not treat current sources as tested')
    raise SystemExit(0)
junit = next(CACHE.glob('modules-2/files-2.1/junit/junit/4.13.2/*/*.jar'))
hamcrest = next(CACHE.glob('modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/*/*.jar'))
run('compile-wire-tests', JDK / 'javac.exe', '-encoding', 'UTF-8', '--release', '17', '-cp',
    os.pathsep.join(map(str, [classes, junit])), '-d', classes, TEST)
run('wire-tests', JDK / 'java.exe', '-cp', os.pathsep.join(map(str, [classes, junit, hamcrest])),
    'org.junit.runner.JUnitCore', 'io.github.yuejie.connect.ClipboardWireCodecTest')

stubs = {
'android/content/Context.java': 'package android.content; public class Context {}',
'android/os/Looper.java': '''package android.os;
public class Looper { public static Looper getMainLooper() { return new Looper(); } }''',
'android/os/Handler.java': '''package android.os;
public class Handler {
 public static final java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
 public Handler(Looper ignored) {}
 public boolean post(Runnable r) { queue.add(r); return true; }
 public static void one() { queue.remove().run(); }
 public static void drain() { while (!queue.isEmpty()) one(); }
}''',
'android/os/SystemClock.java': '''package android.os;
public class SystemClock { public static long now=1000; public static long elapsedRealtime() { return now; } }''',
'org/json/JSONObject.java': '''package org.json;
public class JSONObject {
 public String session;
 public JSONObject(String session) { this.session=session; }
 public String optString(String key) { return session; }
 public String optString(String key, String fallback) { return session==null ? fallback : session; }
}''',
'io/github/yuejie/connect/OplusClipboardEndpoint.java': '''package io.github.yuejie.connect;
class OplusClipboardEndpoint {
 OplusClipboardEndpoint(ClassLoader ignored) throws ReflectiveOperationException {}
 void writeText(android.content.Context context, String text) throws ReflectiveOperationException {
  ClipboardReceiverHarness.write(text);
 }
}''',
'io/github/yuejie/connect/XiaomiClipboardEndpoint.java': '''package io.github.yuejie.connect;
class XiaomiClipboardEndpoint {
 XiaomiClipboardEndpoint(ClassLoader ignored) throws ReflectiveOperationException {}
 void writeText(android.content.Context context, String text) throws ReflectiveOperationException {
  ClipboardReceiverHarness.write(text);
 }
}''',
}

harness = r'''package io.github.yuejie.connect;
import android.os.Handler;
import android.os.SystemClock;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

public final class ClipboardReceiverHarness {
 static final String A="a".repeat(64), B="b".repeat(64);
 static JSONObject projection;
 static boolean allowed;
 static int writes, checks;
 static RuntimeException failure;
 static Runnable duringWrite;
 static void write(String text) {
  writes++;
  if(duringWrite!=null) duringWrite.run();
  if(failure!=null) throw failure;
 }
 record Result(String error, int bytes) {}
 interface Receiver {
  CompletableFuture<Result> execute(String s, String r, String t, long deadline);
  CompletableFuture<Boolean> cancel(String s, String r);
  void changed(); void close();
 }
 static Receiver create(boolean xiaomi) throws Exception {
  projection=new JSONObject(A); allowed=true; writes=0; failure=null; duringWrite=null;
  SystemClock.now=1000; Handler.queue.clear();
  if(xiaomi) {
   var r=new XiaomiClipboardReceiver(new android.content.Context(), null, ()->allowed, ()->projection);
   return new Receiver() {
    public CompletableFuture<Result> execute(String s,String q,String t,long d) {
     return r.execute(s,q,t,d).thenApply(v->new Result(v.error(),v.utf8Bytes()));
    }
    public CompletableFuture<Boolean> cancel(String s,String q) { return r.cancel(s,q); }
    public void changed() { r.sessionChanged(); } public void close() { r.close(); }
   };
  }
  var r=new OplusClipboardReceiver(new android.content.Context(), null, ()->allowed, ()->projection);
  return new Receiver() {
   public CompletableFuture<Result> execute(String s,String q,String t,long d) {
    return r.execute(s,q,t,d).thenApply(v->new Result(v.error(),v.utf8Bytes()));
   }
   public CompletableFuture<Boolean> cancel(String s,String q) { return r.cancel(s,q); }
   public void changed() { r.sessionChanged(); } public void close() { r.close(); }
  };
 }
 static void check(boolean ok,String label) { if(!ok) throw new AssertionError(label); checks++; }
 static void error(CompletableFuture<Result> f,String expected) {
  check(expected.equals(f.join().error()), expected);
 }
 static CompletableFuture<Result> send(Receiver r,String id) { return r.execute(A,id,"synthetic",2000); }
 static void test(boolean xiaomi) throws Exception {
  Receiver r=create(xiaomi);
  var first=send(r,"once"); var duplicate=send(r,"once");
  Handler.one(); check(writes==0&&!first.isDone(),"admission does not write");
  Handler.drain(); check(writes==1&&first.join().bytes()==9&&duplicate.join().error()==null,"duplicate pending");
  var replay=send(r,"once"); Handler.drain(); check(writes==1&&replay.join().error()==null,"terminal replay");
  var reused=r.execute(A,"once","different",2000); Handler.drain(); error(reused,"request_id_reused");
  check(writes==1,"reuse does not write");

  r=create(xiaomi); var cancelled=send(r,"cancel"); var cancel=r.cancel(A,"cancel"); Handler.drain();
  check(cancel.join()&&writes==0,"queued cancel wins"); error(cancelled,"cancelled");
  var again=send(r,"cancel"); Handler.drain(); error(again,"cancelled"); check(writes==0,"cancel tombstone");

  r=create(xiaomi); var expired=send(r,"expire"); Handler.one(); SystemClock.now=2000; Handler.drain();
  error(expired,"request_timeout"); check(writes==0,"expired queued write blocked");
  var old=r.execute(A,"old","synthetic",1999); Handler.drain(); error(old,"expired_or_invalid_deadline");

  r=create(xiaomi); var stale=send(r,"stale"); Handler.one(); projection.session=B; r.changed(); Handler.drain();
  error(stale,"stale_session"); check(writes==0,"changed session blocks queued write");
  var fresh=r.execute(B,"stale","synthetic",2000); Handler.drain(); check(writes==1&&fresh.join().error()==null,"new session independent");

  r=create(xiaomi); var revoked=send(r,"revoked"); Handler.one(); allowed=false; Handler.drain();
  error(revoked,"stale_session"); check(writes==0,"authorization revoked");
  r=create(xiaomi); var closed=send(r,"close"); Handler.one(); r.close(); Handler.drain();
  error(closed,"stale_session"); check(writes==0,"closed receiver");

  r=create(xiaomi); duringWrite=()->projection.session=B; var revokedDuring=send(r,"race"); Handler.drain();
  error(revokedDuring,"stale_session"); check(writes==1,"real write cannot be rolled back by stale receipt");

  r=create(xiaomi); failure=new SecurityException(); var denied=send(r,"denied"); Handler.drain();
  error(denied,"permission_denied"); check(denied.join().bytes()==0,"failure has no receipt byte count");
  failure=null; var deniedReplay=send(r,"denied"); Handler.drain(); error(deniedReplay,"permission_denied");
  check(writes==1,"native failure not silently retried");

  r=create(xiaomi); var applied=send(r,"late"); Handler.drain(); var late=r.cancel(A,"late"); Handler.drain();
  check(!late.join()&&applied.join().error()==null,"late cancellation never claims undo");
  r=create(xiaomi); projection.session="invalid"; var invalid=r.execute("invalid","bad","synthetic",2000);
  Handler.drain(); error(invalid,"stale_session"); check(writes==0,"malformed session");
 }
 public static void main(String[] args) throws Exception {
  test(false); test(true); System.out.println("PASS: "+checks+" receiver assertions; OEM sinks mocked, no devices accessed.");
 }
}'''
stubs['io/github/yuejie/connect/ClipboardReceiverHarness.java'] = harness
stub_dir = OUT / 'receiver-harness-src'
stub_paths = []
for relative, source in stubs.items():
    path = stub_dir / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding='utf-8')
    stub_paths.append(path)
receiver_classes = OUT / 'receiver-classes'
receiver_classes.mkdir(exist_ok=True)
receiver_sources = [MAIN / (n + '.java') for n in
                    ('OplusClipboardReceiver', 'XiaomiClipboardReceiver', 'OplusClipboardTasks', 'OplusClipboardCodec')]
run('compile-receiver-tests', JDK / 'javac.exe', '-encoding', 'UTF-8', '--release', '17',
    '-d', receiver_classes, *stub_paths, *receiver_sources)
run('receiver-tests', JDK / 'java.exe', '-cp', receiver_classes, 'io.github.yuejie.connect.ClipboardReceiverHarness')
hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sources, TEST, Path(__file__)]}
(OUT / 'host-validation.json').write_text(json.dumps(dict(source_sha256=hashes,
    wire_tests=6, receiver_test_output=(OUT / 'receiver-tests.txt').read_text(encoding='utf-8').strip(),
    apk=None, device_operations=False, notes='Receiver tests use scheduler and OEM endpoint test doubles; compile uses real Android/Xposed APIs.'),
    ensure_ascii=False, indent=2), encoding='utf-8')
