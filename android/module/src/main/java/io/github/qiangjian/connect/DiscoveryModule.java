package io.github.qiangjian.connect;

import android.app.Application;
import android.content.Context;
import android.os.Process;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** API 102 no-arg entry. No legacy Xposed APIs and no native loading in host processes. */
public final class DiscoveryModule extends XposedModule {
    private static final String TAG="QiangJian";
    private BridgeClient client;
    private final List<HookHandle> handles=new ArrayList<>();
    private volatile boolean allowed;
    private boolean installed;
    private String processName;
    private ProjectionHooks projectionHooks;
    private ClipboardBridge clipboardBridge;
    private FileTransferBridge fileBridge;
    private ClipboardUriBridge clipboardUris;
    private DeviceFinderBinding finderBinding;
    private AppRelayRuntime relayRuntime;
    private ScreenCastLinkerRuntime screenLinker;
    private ScreenCastSourceRuntime screenSource;
    private ScreenCastSinkRuntime screenSink;
    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        ScreenCastLog.module=this;
        client=new BridgeClient();
        processName=param.getProcessName();
        log(Log.INFO,TAG,"module_loaded api="+getApiVersion()+" process="+param.getProcessName()+" runtime="+BridgeClient.runtimeRevision());
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        String pkg=param.getPackageName();
        if(CallerPolicy.OPLUS_PAIRING.equals(pkg)){
            if(installed||getApiVersion()<102||!param.isFirstPackage())return;installed=true;
            try{OplusNativePairing.install(this,client,param.getClassLoader(),processName);}
            catch(ReflectiveOperationException failure){log(Log.ERROR,TAG,"native_pairing_signature_unsupported");}
            return;
        }
        boolean mirror=CallerPolicy.MIRROR.equals(pkg);
        boolean finder=CallerPolicy.OPLUS_FINDER.equals(pkg),cast=CallerPolicy.OPLUS_CAST.equals(pkg);
        if((finder||cast)&&!pkg.equals(processName))return;
        if(mirror&&!CallerPolicy.MIRROR.equals(processName))return;
        if(CallerPolicy.XIAOMI.equals(pkg)&&!"com.milink.service:core".equals(processName)&&!"com.milink.service:ui".equals(processName))return;
        if(installed || getApiVersion()<102 || !param.isFirstPackage()
                || !(CallerPolicy.XIAOMI.equals(pkg)||CallerPolicy.OPLUS.equals(pkg)||mirror||finder||cast)) return;
        installed=true;
        try {
            int expected=CallerPolicy.XIAOMI.equals(pkg)||mirror?1:2;
            ClassLoader cl=param.getClassLoader();
            handles.add(hook(Application.class.getDeclaredMethod("attach",Context.class))
                .setId("qj.context").intercept(chain->{
                    Object result=chain.proceed();
                    Context context=(Context)chain.getArg(0);
                    if(pkg.equals(context.getPackageName())) {
                        allowed=mirror?CallerVerifier.mirrorUid(context.getPackageManager())==Process.myUid():CallerVerifier.verify(context.getPackageManager(),Process.myUid())==expected;
                        if(allowed) {
                            client.attach(context);
                            if(mirror)new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
                                try{screenSink=new ScreenCastSinkRuntime(this,client,cl);}
                                catch(Exception error){android.util.Log.e("QiangJianScreen","sink_install_failed",error);}
                            });
                            if(cast)new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
                                try{screenSource=new ScreenCastSourceRuntime(this,client,cl);}
                                catch(Exception error){android.util.Log.e("QiangJianScreen","source_install_failed",error);}
                            });
                            if(CallerPolicy.OPLUS.equals(processName))new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
                                try{screenLinker=new ScreenCastLinkerRuntime(client,cl);}
                                catch(Exception error){android.util.Log.e("QiangJianScreen","linker_install_failed",error);}
                            });
                            if(!mirror&&!finder&&!cast)client.nativePairing().register((Application)chain.getThisObject());
                            if(finder||(expected==1&&"com.milink.service:core".equals(processName))){
                                Application app=(Application)chain.getThisObject();
                                handles.add(hook(app.getClass().getMethod("onCreate")).setId("qj.finder.lifecycle").intercept(create->{
                                    Object created=create.proceed();
                                    if(allowed&&finderBinding==null)try{
                                        client.attach(app);
                                        finderBinding=new DeviceFinderBinding(client,cl,expected,this);
                                        log(Log.INFO,TAG,"finder_binding_installed source="+expected);
                                    }catch(Exception failure){log(Log.WARN,TAG,"finder_binding_unavailable "+failure.getClass().getSimpleName());}
                                    return created;
                                }));
                            }
                            if(mirror||(expected==2&&"com.oplus.linker:app".equals(processName)))try {
                                clipboardBridge=new ClipboardBridge(this,client,cl,()->allowed,expected);
                                clipboardBridge.install(context);
                                log(Log.INFO,TAG,"clipboard_bridge_installed source="+expected);
                            }catch(ReflectiveOperationException|RuntimeException clipboardFailure){
                                if(clipboardBridge!=null)clipboardBridge.close();
                                log(Log.WARN,TAG,"clipboard_bridge_unavailable "+clipboardFailure.getClass().getSimpleName());
                            }
                            // ClipboardProvider has no manifest process override. URI/File hooks
                            // must live in Linker's default process; plain/HTML stays in :app.
                            if(mirror||(expected==2&&CallerPolicy.OPLUS.equals(processName)))try{
                                fileBridge=new FileTransferBridge(context,client,()->allowed);
                                clipboardUris=new ClipboardUriBridge(this,context,cl,client,()->allowed,expected,fileBridge);
                                log(Log.INFO,TAG,"file_uri_bridges_installed source="+expected);
                            }catch(java.io.IOException|ReflectiveOperationException|RuntimeException fileFailure){
                                if(clipboardUris!=null)clipboardUris.close();
                                if(fileBridge!=null)fileBridge.close();
                                log(Log.WARN,TAG,"file_uri_bridges_unavailable "+fileFailure.getClass().getSimpleName());
                            }
                            if(mirror||(expected==2&&CallerPolicy.OPLUS.equals(processName))){
                                // Run after the current Application launch transaction. Do not
                                // depend on whether an OEM overrides/inherits Application.onCreate.
                                new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
                                    if(allowed&&relayRuntime==null)relayRuntime=new AppRelayRuntime(this,client,cl,expected,()->allowed,fileBridge);
                                });
                            }
                            if(expected==1&&"com.milink.service:core".equals(processName))try {
                                // This class is absent from release builds. It only triggers the native read-only API.
                                Class.forName("io.github.qiangjian.connect.XiaomiNativeProfileProbe",false,getClass().getClassLoader())
                                    .getMethod("install",Context.class,ClassLoader.class,BridgeClient.class)
                                    .invoke(null,context,cl,client);
                            }catch(ClassNotFoundException releaseBuild){}catch(ReflectiveOperationException probeFailure){
                                log(Log.WARN,TAG,"xiaomi_debug_probe_unavailable");
                            }
                        }
                        log(Log.INFO,TAG,allowed?"caller_verified":"unsupported_package_identity");
                    }
                    return result;
                }));
            if(mirror){
                android.os.IBinder proof=VendorProcessProof.endpoint(client::context,()->allowed);
                handles.add(hook(Class.forName(VendorProcessProof.SERVICE,false,cl).getDeclaredMethod("onBind",android.content.Intent.class))
                    .setId("qj.mirror.process.proof").intercept(chain->{
                        android.content.Intent intent=(android.content.Intent)chain.getArg(0);
                        if(intent!=null&&VendorProcessProof.ACTION.equals(intent.getAction()))return allowed?proof:null;
                        return chain.proceed();
                    }));
                return;
            }
            if(finder||cast)return; // Each package has its own business classes, not linker discovery.
            if(expected==1&&"com.milink.service:ui".equals(processName)){
                projectionHooks=new ProjectionHooks(this,client,expected,cl,()->allowed);projectionHooks.install();
                return;
            }
            String owner=expected==1?"com.miui.circulate.channel.SharedChannelImpl":
                    "com.oplus.linker.connect.arch.discovery.DiscoveryImpl$startSearch$2";
            Class<?> receiver=Class.forName(owner,false,cl);
            Class<?> device=Class.forName(expected==1?"com.xiaomi.continuity.netbus.DeviceInfo":
                    "com.oplus.pantaconnect.sdk.connectionservice.connection.DisplayDevice",false,cl);
            Extractor extract=new Extractor(device,expected);
            for(String method:new String[]{"onDeviceFound","onDeviceLost"}) {
                Class<?>[] args=expected==1?new Class<?>[]{String.class,device}:new Class<?>[]{device};
                install(receiver.getDeclaredMethod(method,args),extract,expected==1?1:0,method.endsWith("Lost")?"lost":"found");
            }
            if(expected==1) install(receiver.getDeclaredMethod("onDeviceInfoChanged",String.class,int.class,device),extract,2,"changed");
            log(Log.INFO,TAG,"discovery_hooks_installed source="+expected);
            try{projectionHooks=new ProjectionHooks(this,client,expected,cl,()->allowed);projectionHooks.install();
                log(Log.INFO,TAG,"projection_hooks_installed source="+expected);
            }catch(ReflectiveOperationException|RuntimeException e){log(Log.ERROR,TAG,"projection_hooks_unsupported "+e.getClass().getSimpleName());}
        } catch(ReflectiveOperationException | RuntimeException e) {
            allowed=false;
            for(HookHandle handle:handles) handle.unhook();
            handles.clear();
            log(Log.ERROR,TAG,"unsupported_hook_signature "+e.getClass().getSimpleName());
        }
    }
    private void install(Method method,Extractor extract,int arg,String kind) {
        if(method.getReturnType()!=void.class) throw new IllegalArgumentException("Unexpected return type");
        handles.add(hook(method).setId("qj."+method.getName()).intercept(chain->{
            Object result=chain.proceed(); // Original result/exception is preserved exactly once.
            if(allowed) try {
                String service=extract.source==1?(String)chain.getArg(0):"";
                if(extract.source==2 || "00070A2A".equals(service))
                    client.offer(extract.copy(chain.getArg(arg),kind,service));
            } catch(ReflectiveOperationException | org.json.JSONException | RuntimeException ignored) {
                // Never log raw vendor objects, identifiers, names, or account data.
            }
            return result;
        }));
    }
    private static final class Extractor {
        final int source; final Method id,name,type,cap,cap2,cap3;
        Extractor(Class<?> device,int source) throws NoSuchMethodException {
            this.source=source;
            id=device.getMethod(source==1?"getDeviceId":"getProtocolDeviceId");
            name=device.getMethod(source==1?"getDeviceName":"getDisplayName");
            type=device.getMethod("getDeviceType");cap=device.getMethod(source==1?"getCapability":"getAbility");
            cap2=source==1?device.getMethod("getCapabilityV2"):null;
            cap3=source==1?device.getMethod("getCapabilityV3"):null;
        }
        JSONObject copy(Object d,String kind,String service) throws ReflectiveOperationException,org.json.JSONException {
            Object t=type.invoke(d);
            String typeText=t instanceof Enum<?>?((Enum<?>)t).name():String.valueOf(t);
            return new JSONObject().put("kind",kind).put("native_id",bounded(id.invoke(d),256))
                .put("name",bounded(name.invoke(d),128)).put("device_type",typeText)
                .put("capability",((Number)cap.invoke(d)).longValue())
                .put("capability_v2",cap2==null?0:((Number)cap2.invoke(d)).longValue())
                .put("capability_v3",cap3==null?0:((Number)cap3.invoke(d)).longValue()).put("service_id",service);
        }
        private String bounded(Object value,int limit) {
            String s=value==null?"":(String)value;
            if(s.length()>limit) throw new IllegalArgumentException("Oversized field");return s;
        }
    }
}
