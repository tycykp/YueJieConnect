"""Exercise production camera result/lifecycle code with explicit host platform/ROM doubles.
No camera, device, APK, original ImageReader or native JNI execution is claimed.
"""
import hashlib
import json
from pathlib import Path
import subprocess
from datetime import datetime

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/camera-validation" / ("native-lifecycle-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f"))
MAIN = ROOT / "android/module/src/main/java/io/github/yuejie/connect"
JAVA = Path(r"C:\Program Files\Android\Android Studio\jbr\bin")
STUBS = {
"android/os/IBinder.java": """package android.os;
public interface IBinder { interface DeathRecipient {void binderDied();}
boolean isBinderAlive(); void linkToDeath(DeathRecipient r,int f) throws RemoteException;
boolean unlinkToDeath(DeathRecipient r,int f); }""",
"android/os/IInterface.java": "package android.os; public interface IInterface { IBinder asBinder(); }",
"android/os/RemoteException.java": "package android.os; public class RemoteException extends Exception {public RemoteException(String m){super(m);}}",
"android/os/SystemClock.java": "package android.os; public final class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}",
"android/os/Looper.java": """package android.os; import java.util.concurrent.*;
public final class Looper {final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});}""",
"android/os/HandlerThread.java": """package android.os;
public final class HandlerThread {private final Looper looper=new Looper(); public HandlerThread(String name){}
public void start(){} public Looper getLooper(){return looper;} public boolean quitSafely(){looper.executor.shutdownNow();return true;}}""",
"android/os/Handler.java": """package android.os; import java.util.concurrent.*;
public final class Handler {final Looper looper; public Handler(Looper l){looper=l;}
public boolean post(Runnable r){try{looper.executor.execute(r);return true;}catch(RejectedExecutionException closed){return false;}}
public boolean postDelayed(Runnable r,long ms){try{looper.executor.schedule(r,ms,TimeUnit.MILLISECONDS);return true;}catch(RejectedExecutionException closed){return false;}}}""",
"android/graphics/ImageFormat.java": "package android.graphics; public final class ImageFormat {public static final int JPEG=256;}",
"android/view/Surface.java": "package android.view; public class Surface {public final long id; public Surface(long id){this.id=id;} public boolean isValid(){return true;}}",
"android/media/Image.java": """package android.media; import java.nio.*;
public class Image {public final ByteBuffer buffer; public boolean closed; final long timestamp;
public Image(byte[] bytes,long t){buffer=ByteBuffer.wrap(bytes).asReadOnlyBuffer();timestamp=t;}
public int getFormat(){return 256;} public long getTimestamp(){return timestamp;}
public Plane[] getPlanes(){return new Plane[]{new Plane()};} public void close(){closed=true;}
public class Plane {public ByteBuffer getBuffer(){return buffer;}}}""",
"android/media/ImageReader.java": """package android.media; import android.view.Surface; import java.util.*; import io.github.libxposed.api.XposedModule;
public class ImageReader {public static XposedModule module; final Surface surface; public boolean closed;
public final Queue<Image> images=new ArrayDeque<>(); public ImageReader(Surface s){surface=s;}
public Surface getSurface(){return surface;} public int getImageFormat(){return 256;} public int getWidth(){return 640;} public int getHeight(){return 480;}
public Image acquireNextImage(){return images.poll();} public Image acquireLatestImage(){
try {Image result=(Image)module.call(this,"acquireNextImage"); Image next;
while((next=(Image)module.call(this,"acquireNextImage"))!=null){if(result!=null)result.close();result=next;}return result;}
catch(Throwable t){throw new RuntimeException(t);}} public void close(){closed=true;}}""",
"android/content/Context.java": """package android.content; public class Context {
public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}}""",
"android/content/pm/PackageManager.java": """package android.content.pm; public class PackageManager {
public String[] getPackagesForUid(int uid){return new String[]{"native.client"};}}""",
"io/github/yuejie/connect/CameraShareStillSource.java": """package io.github.yuejie.connect;
final class CameraShareStillSource { interface Permit {boolean valid(CameraShareStillRequest r);} }""",
"io/github/yuejie/connect/XiaomiCameraHardwareBinding.java": """package io.github.yuejie.connect;
final class XiaomiCameraHardwareBinding {
interface PeerPermit {boolean valid(String s,String p,String n,String d,int c,String id);}
record Source(String id){} record Selection(String nativeDeviceId,String dhId,int nativeCameraId,Source source){
void validate(String s,String p,String n,String d,int c,PeerPermit permit){throw new UnsupportedOperationException("not tested here");}}}""",
"com/xiaomi/camera/companion/util/SurfaceUtils.java": """package com.xiaomi.camera.companion.util;
import android.view.Surface; import java.util.concurrent.*;
public final class SurfaceUtils {public static volatile CountDownLatch entered,release;
public static int nativeGetJpegFooterSize(){return 8;} public static long getSurfaceId(Surface s){return s.id;}
public static void setSurfaceDimens(Surface s,int w,int h){}
public static void produceFrame(Surface s,byte[] b,int w,int h,int f) throws InterruptedException {
CountDownLatch a=entered,r=release;if(a!=null)a.countDown();if(r!=null&&!r.await(2,TimeUnit.SECONDS))throw new IllegalStateException("host writer timed out");}}""",
"vendor/xiaomi/hardware/camera/synthetic/OutputConfiguration.java": "package vendor.xiaomi.hardware.camera.synthetic; public final class OutputConfiguration {}",
"com/xiaomi/vtcamera/injection/VirtualCamera2.java": """package com.xiaomi.vtcamera.injection;
import android.os.IInterface; import android.view.Surface; import java.util.*; import java.util.concurrent.*;
import com.xiaomi.camera.companion.util.SurfaceUtils;
public final class VirtualCamera2 {public int mClientUid=42; public String mPackageName="native.client";
public IInterface mVirtualCameraCallback; public final List<StreamConfiguration> outputs=new ArrayList<>();
public CountDownLatch configEntered,configRelease;
public List<StreamConfiguration> getRequestedOutputStreams(){return new ArrayList<>(outputs);}
public void close(){outputs.clear();if(SurfaceUtils.release!=null)SurfaceUtils.release.countDown();}
public void configureOutputStreams(vendor.xiaomi.hardware.camera.synthetic.OutputConfiguration[] config) throws InterruptedException {
if(configEntered!=null)configEntered.countDown();if(configRelease!=null)configRelease.await(2,TimeUnit.SECONDS);outputs.clear();}
public static final class StreamConfiguration {final Surface surface;public StreamConfiguration(Surface s){surface=s;}
public int usage(){return 5;} public int format(){return 256;} public int width(){return 640;} public int height(){return 480;}
public Surface surface(){return surface;}public long getId(){return surface.id;}}}""",
"io/github/libxposed/api/XposedInterface.java": """package io.github.libxposed.api;
public interface XposedInterface {interface HookHandle {void unhook();}
interface Chain {Object getThisObject();Object proceed() throws Throwable;}}""",
"io/github/libxposed/api/XposedModule.java": """package io.github.libxposed.api;
import java.lang.reflect.*; import java.util.*; import java.util.concurrent.*;
public final class XposedModule {public interface Interceptor {Object call(XposedInterface.Chain c) throws Throwable;}
final Map<Method,Interceptor> hooks=new ConcurrentHashMap<>();
public Builder hook(Method m){return new Builder(m);}public final class Builder {final Method m;Builder(Method m){this.m=m;}
public Builder setId(String id){return this;}public XposedInterface.HookHandle intercept(Interceptor i){hooks.put(m,i);return ()->hooks.remove(m,i);}}
public Object call(Object owner,String name) throws Throwable{return call(owner,name,new Class<?>[0],new Object[0]);}
public Object call(Object owner,String name,Class<?>[] types,Object[] args) throws Throwable {
Method m=owner.getClass().getMethod(name,types);Interceptor i=hooks.get(m);
XposedInterface.Chain chain=new XposedInterface.Chain(){public Object getThisObject(){return owner;}
public Object proceed() throws Throwable{try{return m.invoke(owner,args);}catch(InvocationTargetException e){throw e.getCause();}}};
return i==null?chain.proceed():i.call(chain);}}""",
}

generated = []
for name, content in STUBS.items():
    path = OUT / "stubs" / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    generated.append(path)
production = [MAIN / (name + ".java") for name in [
    "CameraShareStillRequest", "CameraShareJpeg", "CameraShareStillReceipt", "XiaomiCameraStillConsumer",
    "XiaomiCameraNativeRequests", "XiaomiCameraStillResultHooks", "XiaomiCameraStillLifecycleHooks"]]
test = ROOT / "scripts/camera-sharing/CameraShareNativeLifecycleChecks.java"
sources = production + [test] + generated
commands = [
    [JAVA / "javac.exe", "--release", "17", "-encoding", "UTF-8", "-d", OUT / "classes", *sources],
    [JAVA / "java.exe", "-cp", OUT / "classes", "io.github.yuejie.connect.CameraShareNativeLifecycleChecks"],
]
results = []
for command in commands:
    process = subprocess.run([str(arg) for arg in command], cwd=ROOT, capture_output=True,
                             encoding="utf-8", errors="replace", timeout=30)
    result = dict(command=[str(arg) for arg in command], exit_code=process.returncode,
                  output=process.stdout + process.stderr)
    results.append(result)
    if result["output"]:
        print(result["output"].strip())
    if process.returncode:
        break
ledger = dict(result="PASS" if results[-1]["exit_code"] == 0 else "FAIL", commands=results,
              sources=[dict(path=str(p.relative_to(ROOT)), sha256=hashlib.sha256(p.read_bytes()).hexdigest())
                       for p in [*sources, Path(__file__)]],
              scope="Production native generation/lifecycle/reader result code with host Android/OEM/JNI/Xposed doubles. Hardware mapping/authorization/real Camera2/native consumer/APK/devices are not tested. Separate API37/API102 combination compile required.")
(OUT / "result.json").write_text(json.dumps(ledger, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"Evidence: {OUT}")
raise SystemExit(results[-1]["exit_code"])
