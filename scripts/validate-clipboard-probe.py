"""Deterministic tests of the real debug probe, including restoration and privacy guards.

Android/OEM/Xposed doubles deliberately do not dispatch a real cross-device operation. These
checks cannot prove native hook reachability, clipboard permission, or an OEM device result.
"""
import hashlib
import argparse
import importlib.util
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/clipboard-validation/probe-host'
PKG = 'io/github/qiangjian/connect'
PROBE = ROOT / ('android/module/src/debug/java/' + PKG + '/ClipboardNativeProbe.java')
CODEC = ROOT / ('android/module/src/main/java/' + PKG + '/OplusClipboardCodec.java')
PHONE_SOURCE = ROOT / ('android/module/src/main/java/' + PKG + '/OplusClipboardPhoneSource.java')
MIRROR_LEASE = ROOT / ('android/module/src/main/java/' + PKG + '/XiaomiClipboardSourceLease.java')
MIRROR_ENDPOINT = ROOT / ('android/module/src/main/java/' + PKG + '/XiaomiClipboardEndpoint.java')
NATIVE_CLOCK = ROOT / ('android/module/src/main/java/' + PKG + '/ClipboardNativeClock.java')
JDK = Path(os.environ.get('CLIPBOARD_JDK', r'C:\Program Files\Android\Android Studio\jbr')) / 'bin'

stubs = {
'android/os/Bundle.java': 'package android.os; public class Bundle {}',
'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }',
'android/os/SystemClock.java': 'package android.os; public class SystemClock { public static long now=1000; public static long elapsedRealtime(){return now;} public static long uptimeMillis(){return now;} }',
'android/os/Handler.java': '''package android.os;
public class Handler {
 public static final java.util.concurrent.ConcurrentLinkedDeque<Runnable> immediate=new java.util.concurrent.ConcurrentLinkedDeque<>();
 public static final java.util.ArrayList<Runnable> delayed=new java.util.ArrayList<>();
 public Handler(Looper l){} public boolean post(Runnable r){immediate.add(r);return true;}
 public boolean postDelayed(Runnable r,long ms){delayed.add(r);return true;}
 public void removeCallbacks(Runnable r){delayed.removeIf(v->v==r);}
 public static void drain(){while(!immediate.isEmpty())immediate.remove().run();}
}''',
'android/text/TextUtils.java': 'package android.text; public class TextUtils { public static boolean equals(CharSequence a,CharSequence b){return java.util.Objects.equals(a,b);} }',
'android/util/Log.java': '''package android.util;
public class Log { public static final int INFO=4; public static final java.util.List<String> events=new java.util.ArrayList<>();
 public static int i(String tag,String s){events.add(s);return 0;} }''',
'android/Manifest.java': 'package android; public class Manifest { public static class permission { public static final String DUMP="dump"; } }',
'android/content/pm/ApplicationInfo.java': 'package android.content.pm; public class ApplicationInfo { public static final int FLAG_DEBUGGABLE=2; public int flags=2; }',
'android/content/pm/PackageManager.java': '''package android.content.pm;
public class PackageManager { public static final int PERMISSION_GRANTED=0;
 public static class NameNotFoundException extends Exception{} public ApplicationInfo getApplicationInfo(String n,int f){return new ApplicationInfo();} }''',
'android/content/Intent.java': '''package android.content;
public class Intent {
 public String action; public final java.util.Map<String,Object> args=new java.util.HashMap<>();
 public Intent(String a){action=a;} public String getAction(){return action;}
 public String getStringExtra(String k){return (String)args.get(k);} public int getIntExtra(String k,int d){return (int)args.getOrDefault(k,d);}
 public boolean filterEquals(Intent b){return action.equals(b.action);}
 public static final int FLAG_GRANT_READ_URI_PERMISSION=1;
}''',
'android/content/IntentFilter.java': 'package android.content; public class IntentFilter {public final String action; public IntentFilter(String s){action=s;} }',
'android/content/BroadcastReceiver.java': 'package android.content; public abstract class BroadcastReceiver { public abstract void onReceive(Context c,Intent i); }',
'android/content/ClipData.java': '''package android.content;
public class ClipData {
 public static class Item { final CharSequence text; final String html; final Object uri; final Intent intent;
  public Item(CharSequence t){this(t,null,null,null);} public Item(CharSequence t,String h,Object u,Intent i){text=t;html=h;uri=u;intent=i;}
  public Item(android.net.Uri uri){this(null,null,uri,null);}
  public CharSequence getText(){return text;} public String getHtmlText(){return html;} public android.net.Uri getUri(){return uri==null?null:uri instanceof android.net.Uri u?u:android.net.Uri.parse(uri.toString());} public Intent getIntent(){return intent;} }
 private final java.util.List<Item> items=new java.util.ArrayList<>();
 public ClipData(Item i){items.add(i);} public ClipData(ClipData c){items.addAll(c.items);}
 public ClipData(CharSequence label,String[] types,Item i){this(i);}
 public int getItemCount(){return items.size();} public Item getItemAt(int n){return items.get(n);}
 public void addItem(Item i){items.add(i);} public ClipDescription getDescription(){return new ClipDescription();}
 public static ClipData newPlainText(CharSequence l,CharSequence t){return new ClipData(new Item(t));}
 public static ClipData newHtmlText(CharSequence l,CharSequence t,String h){return new ClipData(new Item(t,h,null,null));}
}''',
'android/content/ClipDescription.java': 'package android.content; public class ClipDescription {public boolean hasMimeType(String m){return "text/plain".equals(m)||"text/html".equals(m);}}',
'android/content/ClipboardManager.java': '''package android.content;
public class ClipboardManager { public ClipData clip; public int writes; public boolean denyRead,failWrite;
 public interface OnPrimaryClipChangedListener {void onPrimaryClipChanged();}
 public final java.util.List<OnPrimaryClipChangedListener> listeners=new java.util.ArrayList<>();
 public void addPrimaryClipChangedListener(OnPrimaryClipChangedListener l){listeners.add(l);}
 public void removePrimaryClipChangedListener(OnPrimaryClipChangedListener l){listeners.remove(l);}
 public void platformEvent(){for(OnPrimaryClipChangedListener l:java.util.List.copyOf(listeners))l.onPrimaryClipChanged();}
 public ClipData getPrimaryClip(){if(denyRead)throw new SecurityException();return clip;}
 public boolean hasPrimaryClip(){return clip!=null;} public void setPrimaryClip(ClipData c){if(failWrite)throw new SecurityException();clip=c;writes++;}
 public void clearPrimaryClip(){clip=null;writes++;} }''',
'android/content/Context.java': '''package android.content;
public class Context {
 public static final int RECEIVER_EXPORTED=2; public final ClipboardManager clipboard=new ClipboardManager();
 public String packageName="com.oplus.linker";
 public BroadcastReceiver receiver; public int permission=0; public android.app.Application app;
 public final java.util.List<String> actions=new java.util.ArrayList<>();
 public <T>T getSystemService(Class<T> c){return c.cast(clipboard);} public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
 public Context getApplicationContext(){return app;} public int checkSelfPermission(String s){return permission;}
 public String getPackageName(){return packageName;}
 public java.io.File getCacheDir(){return new java.io.File(System.getProperty("java.io.tmpdir"));}
 public void revokeUriPermission(android.net.Uri u,int flags){}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String p,android.os.Handler h,int flags){receiver=r;actions.add(f.action);return null;}
 public void unregisterReceiver(BroadcastReceiver r){receiver=null;actions.clear();}
}''',
'android/app/Activity.java': 'package android.app; public class Activity { public boolean hasWindowFocus(){return true;} }',
'android/app/Application.java': '''package android.app;
public class Application extends android.content.Context {
 public interface ActivityLifecycleCallbacks {
  void onActivityResumed(Activity a); void onActivityPaused(Activity a); void onActivityCreated(Activity a,android.os.Bundle b);
  void onActivityStarted(Activity a); void onActivityStopped(Activity a); void onActivitySaveInstanceState(Activity a,android.os.Bundle b); void onActivityDestroyed(Activity a);
 }
 public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c){} public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks c){}
}''',
'org/json/JSONException.java': 'package org.json; public class JSONException extends Exception {}',
'org/json/JSONArray.java': '''package org.json; public class JSONArray {
 public int length(){return 1;} public String optString(int i){return "clipboard_write";} }''',
'org/json/JSONObject.java': '''package org.json;
public class JSONObject { public final java.util.Map<String,Object> data=new java.util.LinkedHashMap<>();
 public JSONObject put(String k,Object v)throws JSONException {data.put(k,v);return this;}
 public String optString(String k){return (String)data.getOrDefault(k,"");}
 public int optInt(String k){return (int)data.getOrDefault(k,0);} public JSONObject optJSONObject(String k){return (JSONObject)data.get(k);}
 public JSONArray optJSONArray(String k){return new JSONArray();} public String toString(){return data.toString();} }''',
'io/github/libxposed/api/XposedInterface.java': '''package io.github.libxposed.api;
public interface XposedInterface { interface HookHandle {void unhook();} }''',
'io/github/libxposed/api/XposedModule.java': '''package io.github.libxposed.api;
public class XposedModule {
 public interface Chain { Object getArg(int n); Object proceed() throws Throwable; default Object getThisObject(){return null;} }
 public interface Interceptor { Object run(Chain c) throws Throwable; }
 public static Interceptor converter;
 public static Interceptor source;
 public Builder hook(java.lang.reflect.Method m){return new Builder(m.getName());}
 public boolean deoptimize(java.lang.reflect.Method m){return true;} public void log(int p,String t,String m){}
 public static class Builder { final String name; Builder(String n){name=n;} public Builder setId(String s){return this;} public XposedInterface.HookHandle intercept(Interceptor i){
  if(name.equals("dealClipBoardInfo")){source=i;return ()->source=null;} converter=i;return ()->converter=null;} }
}''',
PKG+'/BridgeClient.java': '''package io.github.qiangjian.connect; class BridgeClient {
 org.json.JSONObject value; org.json.JSONObject projection(){return value;} }''',
PKG+'/ClipboardBridge.java': '''package io.github.qiangjian.connect; class ClipboardBridge {
 record RichReceipt(String peer,String session,String request,int utf8Bytes){}
 interface Probe extends AutoCloseable {boolean permitOutbound(String t); boolean permitInbound(String t); void received(String s,String r,String t,String e);void close();
 default boolean permitUriOutbound(android.content.ClipData c){return true;}default boolean permitUriInbound(String n,long z,String h){return true;}
 default void attachUriTransfers(java.util.function.Supplier<? extends java.util.concurrent.CompletableFuture<?>> s){}
 default void uriStaged(String s,String n,android.content.ClipData c){}default void uriApplied(String s,String n,android.content.ClipData c){}default void uriConsumed(String s,String n){} } }''',
PKG+'/ClipboardUriBridge.java': 'package io.github.qiangjian.connect;class ClipboardUriBridge{record Receipt(String peer,String session,String request,String transfer,long size,String sha256){}}',
'android/net/Uri.java': 'package android.net;public record Uri(String value){public static Uri parse(String s){return new Uri(s);}}',
PKG+'/OplusClipboardAdapter.java': '''package io.github.qiangjian.connect; class OplusClipboardAdapter {
 interface Transport {boolean ready(org.json.JSONObject p);java.util.concurrent.CompletableFuture<Receipt> write(String i,String s,String r,String t);java.util.concurrent.CompletableFuture<Boolean> cancel(String i,String s,String r);}
 record Receipt(String id,String session,String request,int utf8Bytes){} }''',
PKG+'/OplusClipboardEndpoint.java': '''package io.github.qiangjian.connect; class OplusClipboardEndpoint {
 record Snapshot(String text,long timestamp){}
 static String text="synthetic"; static long stamp=1; OplusClipboardEndpoint(ClassLoader l){} String readText(){return text;} Snapshot snapshot(){return new Snapshot(text,stamp);} }''',
'com/oplus/linker/synergy/wisetransfer/clipboard/scene/ClipBoardDataBusScene.java': '''package com.oplus.linker.synergy.wisetransfer.clipboard.scene;
public class ClipBoardDataBusScene { public static final Companion Companion=new Companion();
 public static class Companion {public boolean repeated;public boolean isClipDataRepeat(){return repeated;}}
}''',
'com/oplus/linker/synergy/common/utils/ConnectPCUtil.java': '''package com.oplus.linker.synergy.common.utils;
public class ConnectPCUtil {public static boolean enabled=true;public static boolean getClipboardSwitchState(android.content.Context c){return enabled;}}''',
'com/oplus/linker/synergy/wisetransfer/ICDFSceneManager.java': '''package com.oplus.linker.synergy.wisetransfer;
public class ICDFSceneManager {
 public android.content.Context context=new android.content.Context(); public int originalCalls;
 public android.content.Context getMContext(){return context;} private void dealClipBoardInfo(){} public void execute(int n,Object p,Object c){}
 public void event()throws Throwable {
  io.github.libxposed.api.XposedModule.source.run(new io.github.libxposed.api.XposedModule.Chain(){
    public Object getArg(int i){return null;} public Object getThisObject(){return ICDFSceneManager.this;}
    public Object proceed(){originalCalls++;return null;}
  });
 }
}''',
'com/oplus/linker/synergy/wisetransfer/clipboard/ClipBoardServiceHelper.java': '''package com.oplus.linker.synergy.wisetransfer.clipboard;
public class ClipBoardServiceHelper {
 public static int restores; public static void copyCompleteDataToClip(android.content.ClipData data,android.content.Context c){restores++;c.clipboard.setPrimaryClip(data);} }''',
'com/xiaomi/mirror/k0.java': 'package com.xiaomi.mirror; public class k0 {}',
'com/xiaomi/mirror/Mirror.java': '''package com.xiaomi.mirror;
public class Mirror {public static Mirror singleton=new Mirror();public android.content.ClipboardManager clipboard=new android.content.ClipboardManager();public static Mirror C(){return singleton;}public android.content.ClipboardManager t(){return clipboard;}}''',
'A3/b.java': '''package A3;
public class b {public static b singleton=new b();public c a;public static b b(){return singleton;}
 public static class c implements android.content.ClipboardManager.OnPrimaryClipChangedListener {
  public static int events;public c(){}public void onPrimaryClipChanged(){events++;}
 }}''',
'com/xiaomi/mirror/message/ClipDataMessage.java': '''package com.xiaomi.mirror.message;
public class ClipDataMessage { public long clock;public java.util.List<Object> dataList=new java.util.ArrayList<>(),fileList=new java.util.ArrayList<>();
 public static class Data {public int type;public String textType,url;public NativeBytes content;}
 public static class NativeBytes {public byte[] value;public static NativeBytes m(String s,java.nio.charset.Charset c){NativeBytes b=new NativeBytes();b.value=s.getBytes(c);return b;}public byte[] C(){return value;}public int size(){return value.length;}}
}''',
'com/xiaomi/mirror/message/ClipboardMessage.java': 'package com.xiaomi.mirror.message; public class ClipboardMessage { public ClipDataMessage data; }',
'V3/V.java': '''package V3;
public class V {
 public static final V value=new V(); public static android.content.ClipboardManager sink; public static int receives;public static long lastClock;
 public static V G(){return value;}
 public android.content.ClipData z0(com.xiaomi.mirror.message.ClipDataMessage clip,boolean b){
  if(clip.dataList.isEmpty())throw new AssertionError("Original restore must stay local");
  var data=(com.xiaomi.mirror.message.ClipDataMessage.Data)clip.dataList.get(0);
  return android.content.ClipData.newPlainText("mirror",new String(data.content.value,java.nio.charset.StandardCharsets.UTF_8));
 }
 public void T(com.xiaomi.mirror.k0 peer,com.xiaomi.mirror.message.ClipboardMessage m)throws Exception {
  if(m.data.clock<=lastClock)return;lastClock=m.data.clock;receives++;
  try {
   android.content.ClipData result=io.github.libxposed.api.XposedModule.converter==null?z0(m.data,true):(android.content.ClipData)io.github.libxposed.api.XposedModule.converter.run(new io.github.libxposed.api.XposedModule.Chain(){
    public Object getArg(int i){return i==0?m.data:true;} public Object proceed(){return z0(m.data,true);}
   });
   if(result==null)sink.clearPrimaryClip();else sink.setPrimaryClip(result);
  }catch(Throwable e){throw new Exception(e);}
 }
}''',
}

harness = r'''package io.github.qiangjian.connect;
import android.content.*;
import android.os.Handler;
import android.util.Log;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
public class ClipboardProbeHarness {
 static final String RUN="b".repeat(32),SESSION="a".repeat(64),PRIVATE="PRIVATE never log or transmit";
 static int assertions;
 static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);assertions++;}
 static String marker(int leg){return "QJ_CLIP_"+RUN+"_"+leg+"_中文🙂\n\t";}
 static void command(Context c,String op,int leg){android.os.SystemClock.now++;Intent i=new Intent("io.github.qiangjian.connect.CLIPBOARD_NATIVE_PROBE");i.args.put("run",RUN);i.args.put("op",op);i.args.put("leg",leg);c.receiver.onReceive(c,i);Handler.drain();}
 static boolean event(String value){return Log.events.stream().anyMatch(e->e.contains("result="+value+"}"));}
 static void test(int brand)throws Exception {
  Log.events.clear();Handler.delayed.clear();Handler.immediate.clear();
  Context context=new Context();context.app=new android.app.Application();
  ClipData original=new ClipData(new ClipData.Item(PRIVATE,"<b>private</b>","content://private",new Intent("private")));
  context.clipboard.clip=original;V3.V.sink=context.clipboard;
  BridgeClient client=new BridgeClient();client.value=new org.json.JSONObject().put("session_id",SESSION);
  AtomicReference<CompletableFuture<OplusClipboardAdapter.Receipt>> transfer=new AtomicReference<>();
  ClipboardBridge.Probe probe=ClipboardNativeProbe.install(context,new io.github.libxposed.api.XposedModule(),ClipboardProbeHarness.class.getClassLoader(),client,brand,transfer::get);
  context.permission=-1;command(context,"prepare",0);
  check(event("requires_focused_native_activity"),"Denied background snapshot must fail");
  check(context.clipboard.writes==0,"Denied snapshot must not mutate");
  context.permission=0;command(context,"prepare",0);check(event("armed"),"arm");
  check(!probe.permitOutbound(PRIVATE),"Only synthetic outbound while armed");
  check(probe.permitOutbound(marker(1)),"Synthetic outbound allowed");
  check(!probe.permitInbound("not synthetic"),"Unrelated inbound cannot overwrite snapshot");
  command(context,"copy",1);check(event("platform_copy"),"Real platform event");
  check(transfer.get()==null,"Probe must not generate a transport transfer");
  check(!event("remote_ack"),"No fake result after copy");
  CompletableFuture<OplusClipboardAdapter.Receipt> actual=new CompletableFuture<>();transfer.set(actual);
  // Only the observation callback; expiry remains queued for later.
  Runnable observer=Handler.delayed.remove(Handler.delayed.size()-1);observer.run();
  check(!event("remote_ack"),"Wait for actual future completion");
  actual.complete(new OplusClipboardAdapter.Receipt("peer",SESSION,"request",OplusClipboardCodec.encode(marker(1)).length));Handler.drain();
  check(event("remote_ack"),"Record bound receipt");
  probe.received(SESSION,"wire",marker(2),"native_failure");check(event("failed"),"No sink success on failure");
  command(context,"finish",0);check(event("restored"),"Restore validated");
  check(context.clipboard.clip==original||context.clipboard.clip.getItemAt(0)==original.getItemAt(0),"Original rich item preserved in process");
  check(!probe.permitOutbound(PRIVATE),"Late original content must not be resent");
  check(!probe.permitInbound(marker(1)),"Late test inbound must not overwrite restored clipboard");
  check(probe.permitOutbound("next user copy"),"New user event releases guard");
  check(probe.permitOutbound(PRIVATE),"Normal behavior restored after new copy");
  command(context,"prepare",0);command(context,"copy",1);
  context.clipboard.clip=ClipData.newPlainText("user","user changed while test running");
  int writes=context.clipboard.writes;command(context,"finish",0);
  check(event("preserved_new_clipboard"),"Concurrent user copy wins");
  check(writes==context.clipboard.writes,"Do not overwrite newer user content");
  command(context,"prepare",0);command(context,"copy",1);context.clipboard.failWrite=true;
  command(context,"finish",0);check(event("failed_retry_pending"),"Failed restore retains snapshot");
  context.clipboard.failWrite=false;command(context,"finish",0);
  check("user changed while test running".contentEquals(context.clipboard.clip.getItemAt(0).getText()),"Restoration retry succeeds");
  check(Log.events.stream().noneMatch(e->e.contains(PRIVATE)||e.contains("content://private")||e.contains("<b>")),"Logs exclude private content");
  probe.close();
 }
 public static void main(String[] args)throws Exception{test(2);test(1);System.out.println("PASS "+assertions+" probe assertions; OEM/Android doubles, not device evidence");}
}'''

source_harness = r'''package io.github.qiangjian.connect;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import android.os.Handler;
import org.json.JSONObject;
import com.oplus.linker.synergy.common.utils.ConnectPCUtil;
import com.oplus.linker.synergy.wisetransfer.ICDFSceneManager;
public class ClipboardPhoneSourceHarness {
 static final String SESSION="a".repeat(64); static int checks;
 static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);checks++;}
 static void event(ICDFSceneManager scene)throws Throwable {OplusClipboardEndpoint.stamp++;scene.event();}
 static class Transport implements OplusClipboardAdapter.Transport {
  int writes,cancels; String id,session,request,text; CompletableFuture<OplusClipboardAdapter.Receipt> pending;
  public boolean ready(JSONObject p){return p!=null;}
  public CompletableFuture<OplusClipboardAdapter.Receipt> write(String i,String s,String r,String t){writes++;id=i;session=s;request=r;text=t;return pending=new CompletableFuture<>();}
  public CompletableFuture<Boolean> cancel(String i,String s,String r){cancels++;return CompletableFuture.completedFuture(true);}
  void ack(String i){pending.complete(new OplusClipboardAdapter.Receipt(i,session,request,OplusClipboardCodec.encode(text).length));Handler.drain();}
 }
 public static void main(String[] args)throws Throwable {
  Handler.delayed.clear();Handler.immediate.clear();
  JSONObject p=new JSONObject().put("id","peer").put("session_id",SESSION).put("sdk_device_type",10).put("real",new JSONObject().put("kind","tablet"));
  AtomicReference<JSONObject> projection=new AtomicReference<>(p);
  java.util.concurrent.atomic.AtomicBoolean allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
  AtomicReference<CompletableFuture<OplusClipboardAdapter.Receipt>> observed=new AtomicReference<>();
  Transport t=new Transport();ICDFSceneManager nativeScene=new ICDFSceneManager();
  OplusClipboardPhoneSource source=new OplusClipboardPhoneSource(new io.github.libxposed.api.XposedModule(),ClipboardPhoneSourceHarness.class.getClassLoader(),allowed::get,projection::get,t,observed::set);
  source.install();
  check(t.writes==0,"Installing never sends clipboard");
  ConnectPCUtil.enabled=false;event(nativeScene);Handler.drain();check(t.writes==0,"Native switch off blocks");
  ConnectPCUtil.enabled=true;p.put("sdk_device_type",6);event(nativeScene);Handler.drain();check(t.writes==0,"PC not a tablet");
  p.put("sdk_device_type",10);p.optJSONObject("real").put("kind","phone");event(nativeScene);Handler.drain();check(t.writes==0,"Must preserve real role");
  p.optJSONObject("real").put("kind","tablet");allowed.set(false);event(nativeScene);Handler.drain();check(t.writes==0,"Permission required");allowed.set(true);
  event(nativeScene);ConnectPCUtil.enabled=false;Handler.drain();check(t.writes==0,"Recheck native switch after scheduling");ConnectPCUtil.enabled=true;
  OplusClipboardEndpoint.text="synthetic A";event(nativeScene);OplusClipboardEndpoint.text="synthetic B";Handler.drain();
  check(t.writes==1&&t.text.equals("synthetic A"),"Snapshot belongs to native event");
  check(nativeScene.originalCalls==6,"Original native recipients preserved");
  CompletableFuture<OplusClipboardAdapter.Receipt> active=observed.get();check(!active.isDone(),"No ack before remote result");
  event(nativeScene);Handler.drain();check(t.writes==1&&observed.get()==active,"Busy preserves first future");
  t.ack("wrong-peer");check(active.isCompletedExceptionally(),"Wrong receipt rejected");check(t.cancels==1,"Failed result cancellation");
  event(nativeScene);Handler.drain();active=observed.get();t.ack("peer");check(active.isDone()&&!active.isCompletedExceptionally(),"Bound real ack accepted");
  int writes=t.writes;nativeScene.event();Handler.drain();check(t.writes==writes,"Same platform copy seen by native dispatch is not resent");
  source.update(nativeScene.context,false);check(nativeScene.context.clipboard.listeners.isEmpty(),"No platform listener without grant");
  source.update(nativeScene.context,true);source.update(nativeScene.context,true);check(nativeScene.context.clipboard.listeners.size()==1,"One owned listener");
  check(t.writes==writes,"Registering listener does not read/send clipboard");
  com.oplus.linker.synergy.wisetransfer.clipboard.scene.ClipBoardDataBusScene.Companion.repeated=true;
  OplusClipboardEndpoint.stamp++;nativeScene.context.clipboard.platformEvent();Handler.drain();check(t.writes==writes,"OEM receive-loop suppression respected");
  com.oplus.linker.synergy.wisetransfer.clipboard.scene.ClipBoardDataBusScene.Companion.repeated=false;
  OplusClipboardEndpoint.stamp++;nativeScene.context.clipboard.platformEvent();Handler.drain();check(t.writes==writes+1,"Real platform event sends authorized peer");
  t.ack("peer");nativeScene.event();Handler.drain();check(t.writes==writes+1,"Metis duplicate after platform ack is ignored");
  source.update(nativeScene.context,false);check(nativeScene.context.clipboard.listeners.isEmpty(),"Revoking detaches owned listener");
  event(nativeScene);Handler.drain();active=observed.get();projection.set(null);source.sessionChanged();Handler.drain();
  check(active.isCompletedExceptionally(),"Session loss terminates pending");check(t.cancels==2,"Session loss propagates cancel");
  t.ack("peer");check(active.isCompletedExceptionally(),"Late ack cannot resurrect request");projection.set(p);
  event(nativeScene);Handler.drain();active=observed.get();Runnable timeout=Handler.delayed.get(Handler.delayed.size()-1);timeout.run();
  check(active.isCompletedExceptionally(),"Timeout fails result");check(t.cancels==3,"Timeout sends cancellation");
  event(nativeScene);Handler.drain();active=observed.get();source.update(nativeScene.context,true);source.close();Handler.drain();
  check(active.isCompletedExceptionally()&&t.cancels==4,"Close cancels pending");
  check(io.github.libxposed.api.XposedModule.source==null,"Close removes native hook");
  check(nativeScene.context.clipboard.listeners.isEmpty(),"Close removes system listener");
  System.out.println("PASS "+checks+" phone-source assertions; OEM hook/transport doubles, not device evidence");
 }
}'''

lease_harness = r'''package io.github.qiangjian.connect;
import android.content.ClipboardManager;
import com.xiaomi.mirror.Mirror;
public class ClipboardMirrorLeaseHarness {
 static int checks;static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 public static void main(String[] args)throws Exception {
  ClipboardManager initial=Mirror.C().clipboard;
  XiaomiClipboardSourceLease lease=new XiaomiClipboardSourceLease(ClipboardMirrorLeaseHarness.class.getClassLoader());
  lease.update(false);check(initial.listeners.isEmpty(),"No listener before consent");
  lease.update(true);check(initial.listeners.size()==1,"Register real OEM listener");
  check(initial.listeners.get(0).getClass()==A3.b.c.class,"Retain native implementation");
  check(A3.b.c.events==0,"No synthetic initial event or clipboard read");
  Object owned=initial.listeners.get(0);lease.update(true);check(initial.listeners.size()==1&&initial.listeners.get(0)==owned,"Idempotent lifecycle");
  initial.platformEvent();check(A3.b.c.events==1,"Platform event reaches OEM listener");
  A3.b.c nativeOwned=new A3.b.c();A3.b.b().a=nativeOwned;initial.addPrimaryClipChangedListener(nativeOwned);
  lease.update(true);check(initial.listeners.size()==1&&initial.listeners.get(0)==nativeOwned,"Defer to existing OEM listener");
  lease.update(false);check(initial.listeners.size()==1,"Revoking projection must preserve OEM listener");
  initial.removePrimaryClipChangedListener(nativeOwned);A3.b.b().a=null;lease.update(true);
  check(initial.listeners.size()==1,"Resume own native listener after OEM lifecycle ends");
  Mirror.C().clipboard=new ClipboardManager();lease.update(true);
  check(initial.listeners.isEmpty(),"Resource replacement detaches old listener");
  check(Mirror.C().clipboard.listeners.size()==1,"Resource replacement attaches to current manager");
  lease.close();check(Mirror.C().clipboard.listeners.isEmpty(),"Close removes only owned listener");
  lease.update(true);Mirror.singleton=null;lease.update(true);check(true,"Unavailable resource releases owned listener");
  System.out.println("PASS "+checks+" native listener lifecycle assertions; Android/OEM doubles, not device proof");
 }
}'''

endpoint_harness = r'''package io.github.qiangjian.connect;
import android.content.*;
import com.xiaomi.mirror.Mirror;
import com.xiaomi.mirror.message.*;
public class ClipboardMirrorEndpointHarness {
 static int checks;static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 interface Call {void run()throws Exception;}
 static void rejects(Call call)throws Exception {try{call.run();throw new AssertionError("accepted unsupported/lossy source");}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
 static ClipboardMessage message(String text){var data=new ClipDataMessage.Data();data.content=ClipDataMessage.NativeBytes.m(text,java.nio.charset.StandardCharsets.UTF_8);var clip=new ClipDataMessage();clip.dataList.add(data);var m=new ClipboardMessage();m.data=clip;return m;}
 public static void main(String[] args)throws Exception {
  var endpoint=new XiaomiClipboardEndpoint(ClipboardMirrorEndpointHarness.class.getClassLoader());
  check(endpoint.available(),"Native resources ready");
  var m=message("synthetic");Mirror.C().clipboard.clip=ClipData.newPlainText("test","synthetic");
  check(endpoint.readSourceMessage(m).equals("synthetic"),"Matching single plain text accepted");
  Mirror.C().clipboard.clip=new ClipData(new ClipData.Item("synthetic","<b>synthetic</b>",null,null));rejects(()->endpoint.readSourceMessage(m));
  Mirror.C().clipboard.clip=new ClipData(new ClipData.Item("synthetic",null,"content://test",null));rejects(()->endpoint.readSourceMessage(m));
  Mirror.C().clipboard.clip=new ClipData(new ClipData.Item("synthetic",null,null,new Intent("test")));rejects(()->endpoint.readSourceMessage(m));
  Mirror.C().clipboard.clip=ClipData.newPlainText("test","synthetic");Mirror.C().clipboard.clip.addItem(new ClipData.Item("second"));rejects(()->endpoint.readSourceMessage(m));
  Mirror.C().clipboard.clip=ClipData.newPlainText("test","changed");rejects(()->endpoint.readSourceMessage(m));
  Mirror.C().clipboard.clip=ClipData.newPlainText("test","synthetic");m.data.fileList.add(new Object());rejects(()->endpoint.readSourceMessage(m));
  Context c=new Context();c.packageName="com.xiaomi.mirror";V3.V.sink=Mirror.C().clipboard;
  android.os.SystemClock.now=3000;V3.V.lastClock=3000;
  endpoint.writeText(c,"one");check("one".contentEquals(V3.V.sink.clip.getItemAt(0).getText()),"Write after same-millisecond native source cache");
  endpoint.writeText(c,"two");check("two".contentEquals(V3.V.sink.clip.getItemAt(0).getText()),"Second distinct write at same uptime is applied");
  check(V3.V.receives==2,"Both writes actually reached native sink");
  V3.V.lastClock=99999;rejects(()->endpoint.writeText(c,"must not falsely ack"));
  System.out.println("PASS "+checks+" Mirror source-format/clock/readback assertions; OEM/Android doubles, not device proof");
 }
}'''


def run(name, *args):
    result = subprocess.run([str(a) for a in args], capture_output=True, cwd=ROOT)
    text = result.stdout.decode('utf-8', 'replace') + result.stderr.decode('utf-8', 'replace')
    (OUT / (name + '.txt')).write_text(text, encoding='utf-8')
    if result.returncode:
        raise RuntimeError(name + ' failed; see ' + str(OUT / (name + '.txt')))
    print(text.strip() or name + ': PASS')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-only', action='store_true')
    parser.add_argument('--lease-only', action='store_true')
    parser.add_argument('--endpoint-only', action='store_true')
    parser.add_argument('--probe-only', action='store_true', help='Only changed probe and evidence checks; reuse unchanged source/lease tests')
    args = parser.parse_args()
    sources = OUT / 'sources'
    classes = OUT / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    stubs[PKG + '/ClipboardProbeHarness.java'] = harness
    stubs[PKG + '/ClipboardPhoneSourceHarness.java'] = source_harness
    stubs[PKG + '/ClipboardMirrorLeaseHarness.java'] = lease_harness
    stubs[PKG + '/ClipboardMirrorEndpointHarness.java'] = endpoint_harness
    for path, body in stubs.items():
        target = sources / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body, encoding='utf-8')
    run('compile', JDK / 'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-d', classes,
        PROBE, CODEC, PHONE_SOURCE, MIRROR_LEASE, MIRROR_ENDPOINT, NATIVE_CLOCK, *sorted(sources.rglob('*.java')))
    if args.endpoint_only:
        run('mirror-endpoint-tests', JDK / 'java.exe', '-cp', classes, 'io.github.qiangjian.connect.ClipboardMirrorEndpointHarness')
        tested = [MIRROR_ENDPOINT, NATIVE_CLOCK, ROOT / ('android/module/src/main/java/' + PKG + '/XiaomiClipboardAdapter.java'), Path(__file__)]
        (OUT / 'mirror-endpoint-evidence.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-probe.py --endpoint-only',
            source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in tested},
            result='passed', apk=None, device_operations=False, scope='Real source-format/clock/readback endpoint with Android/OEM doubles'), indent=2), encoding='utf-8')
        return
    if args.lease_only:
        run('mirror-lease-tests', JDK / 'java.exe', '-cp', classes, 'io.github.qiangjian.connect.ClipboardMirrorLeaseHarness')
        tested = [MIRROR_LEASE, Path(__file__)]
        (OUT / 'mirror-lease-evidence.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-probe.py --lease-only',
            source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in tested},
            result='passed', apk=None, device_operations=False, scope='Real native source lease with Android/OEM doubles'), indent=2), encoding='utf-8')
        return
    if not args.probe_only:
        run('phone-source-tests', JDK / 'java.exe', '-cp', classes, 'io.github.qiangjian.connect.ClipboardPhoneSourceHarness')
    if args.source_only:
        tested = [PHONE_SOURCE, CODEC, Path(__file__)]
        (OUT / 'phone-source-evidence.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-probe.py --source-only',
            source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in tested},
            result='passed', apk=None, device_operations=False, scope='OEM hook/transport doubles; not native device proof'), indent=2), encoding='utf-8')
        return
    run('probe-tests', JDK / 'java.exe', '-cp', classes, 'io.github.qiangjian.connect.ClipboardProbeHarness')
    spec = importlib.util.spec_from_file_location('native_evidence', ROOT / 'scripts/validate-clipboard-native.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    run_id = 'b' * 32
    session = 'a' * 64

    def event(source, stage, result, leg=1):
        return dict(run=run_id, session=session, source=source, stage=stage, result=result, leg=leg)

    source = [event(1, 'source', 'platform_copy'), event(1, 'receipt', 'remote_ack')]
    sink = [event(2, 'sink', 'native_write_readback'), event(2, 'readback', 'matches')]
    module.assert_direction(source, sink, 1)
    rejected = 0
    for a, b in [(source[:1], sink), (source, sink[:1]), (source, sink[1:]),
                 (source, [dict(sink[0], session='c' * 64), sink[1]]),
                 ([source[0], dict(source[1], result='failed')], sink)]:
        try:
            module.assert_direction(a, b, 1)
        except RuntimeError:
            rejected += 1
    assert rejected == 5
    assert module.parse_events(json.dumps(dict(source[0], payload='private')) + '\n' + json.dumps(source[0]), run_id) == [source[0]]
    print('PASS 7 evidence checks: required source/sink/ack/readback/session and private-field filtering')
    tested = [PROBE, CODEC, Path(__file__), ROOT / 'scripts/validate-clipboard-native.py']
    (OUT / 'evidence.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-probe.py' + (' --probe-only' if args.probe_only else ''),
        source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in tested},
        result='passed', apk=None, device_operations=False, evidence_checks=7,
        scope='Real debug probe with deterministic Android/OEM doubles; does not prove native device behavior'), indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
