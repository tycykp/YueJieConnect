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
    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        client=new BridgeClient();
        processName=param.getProcessName();
        log(Log.INFO,TAG,"module_loaded api="+getApiVersion()+" process="+param.getProcessName());
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        String pkg=param.getPackageName();
        if(CallerPolicy.XIAOMI.equals(pkg)&&!"com.milink.service:core".equals(processName)&&!"com.milink.service:ui".equals(processName))return;
        if(installed || getApiVersion()<102 || !param.isFirstPackage()
                || !(CallerPolicy.XIAOMI.equals(pkg)||CallerPolicy.OPLUS.equals(pkg))) return;
        installed=true;
        try {
            int expected=CallerPolicy.XIAOMI.equals(pkg)?1:2;
            ClassLoader cl=param.getClassLoader();
            handles.add(hook(Application.class.getDeclaredMethod("attach",Context.class))
                .setId("qj.context").intercept(chain->{
                    Object result=chain.proceed();
                    Context context=(Context)chain.getArg(0);
                    if(pkg.equals(context.getPackageName())) {
                        allowed=CallerVerifier.verify(context.getPackageManager(),Process.myUid())==expected;
                        if(allowed) {
                            client.attach(context);
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
