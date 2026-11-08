package io.github.qiangjian.connect;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.HookHandle;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Sample-specific shared-channel adapter. Never creates a pretend Continuity Channel. */
final class XiaomiProtocolAdapter {
    private final XposedModule module;
    private final BridgeClient client;
    private final ClassLoader loader;
    private final BooleanSupplier allowed;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<HookHandle> handles=new ArrayList<>();
    private final List<WeakReference<Object>> receivers=new ArrayList<>();
    private final Map<Object,String> connections=new IdentityHashMap<>();
    private final AtomicLong requests=new AtomicLong();
    private volatile JSONObject current;
    private volatile boolean closed;
    private long generation;
    private Object currentDevice;
    private Method found,lost;
    private Field context;
    XiaomiProtocolAdapter(XposedModule module,BridgeClient client,ClassLoader loader,BooleanSupplier allowed){
        this.module=module;this.client=client;this.loader=loader;this.allowed=allowed;
    }
    private Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,false,loader);}
    void install()throws ReflectiveOperationException{
        try{
            Class<?> owner=type("com.miui.circulate.channel.SharedChannelImpl");
            Class<?> device=type("com.xiaomi.continuity.netbus.DeviceInfo");
            found=owner.getDeclaredMethod("onDeviceFound",String.class,device);
            lost=owner.getDeclaredMethod("onDeviceLost",String.class,device);
            context=owner.getDeclaredField("context");context.setAccessible(true);
            handles.add(module.hook(owner.getDeclaredMethod("onInitialize")).setId("qj.xiaomi.init").intercept(chain->{
                Object result=chain.proceed();Object receiver=chain.getThisObject();main.post(()->add(receiver));return result;
            }));
            handles.add(module.hook(owner.getDeclaredMethod("onRelease")).setId("qj.xiaomi.release").intercept(chain->{
                Object receiver=chain.getThisObject();invalidate(receiver);main.post(()->remove(receiver));return chain.proceed();
            }));
            handles.add(module.hook(owner.getDeclaredMethod("removeCaller",String.class)).setId("qj.xiaomi.removeCaller").intercept(chain->{
                if("deviceprofile".equals(chain.getArg(0)))invalidate(chain.getThisObject());return chain.proceed();
            }));
            for(Class<?>[] signature:new Class<?>[][]{{String.class,String.class},{String.class,String.class,int.class}}){
                handles.add(module.hook(owner.getDeclaredMethod("shareChannel",signature)).setId("qj.xiaomi.connect."+signature.length).intercept(chain->{
                    String id=(String)chain.getArg(1);if(!owns(id))return chain.proceed();
                    if(!"deviceprofile".equals(chain.getArg(0)))return -12;
                    return connect(chain.getThisObject(),id);
                }));
            }
            handles.add(module.hook(owner.getDeclaredMethod("shareChannel",String.class,int.class)).setId("qj.xiaomi.connect.short").intercept(chain->{
                // This overload carries no caller. No generic native channel is implemented.
                if(owns((String)chain.getArg(0)))return -12;return chain.proceed();
            }));
            handles.add(module.hook(owner.getDeclaredMethod("send",String.class,String.class,byte[].class)).setId("qj.xiaomi.send").intercept(chain->{
                String id=(String)chain.getArg(1);if(!owns(id))return chain.proceed();
                if(!"deviceprofile".equals(chain.getArg(0)))return -12;
                return send(chain.getThisObject(),id,(byte[])chain.getArg(2));
            }));
            client.onProjection(this::update);
        }catch(ReflectiveOperationException|RuntimeException failure){close();throw failure;}
    }
    private boolean owns(String id){
        JSONObject p=current;
        return !closed&&allowed.getAsBoolean()&&p!=null&&id!=null&&id.equals(p.optString("id"))&&!p.optString("session_id").isEmpty();
    }
    private boolean supports(JSONObject projection,String operation){
        var operations=projection.optJSONArray("operations");if(operations==null)return false;
        for(int i=0;i<operations.length();i++)if(operation.equals(operations.optString(i)))return true;
        return false;
    }
    private synchronized void invalidate(Object receiver){connections.remove(receiver);generation++;}
    private int connect(Object receiver,String id){
        JSONObject p=current;if(p==null||!supports(p,"battery_status"))return -12;
        String session=p.optString("session_id");long ticket;
        synchronized(this){ticket=generation;}
        JSONObject reply=client.requestBlocking(id,"device_profile","xiaomi-connect-"+requests.incrementAndGet(),new JSONObject());
        synchronized(this){
            if(ticket!=generation||!matches(id,session,reply))return -11;
            connections.put(receiver,session);
        }
        log("deviceprofile_channel_ready");return 0;
    }
    private boolean matches(String id,String session,JSONObject reply){
        JSONObject p=current,live=client.projection();
        return owns(id)&&p!=null&&live!=null&&session.equals(p.optString("session_id"))&&session.equals(live.optString("session_id"))
            &&reply!=null&&"ok".equals(reply.optString("status"))&&session.equals(reply.optString("session_id"));
    }
    private int send(Object receiver,String id,byte[] request){
        if(!XiaomiDeviceProfileCodec.accepts(request))return -12;
        final byte[] copied=request.clone();String session;long ticket;
        synchronized(this){session=connections.get(receiver);ticket=generation;}
        if(session==null)return -12;
        JSONObject reply=client.requestBlocking(id,"battery_status","xiaomi-battery-"+requests.incrementAndGet(),new JSONObject());
        try{
            synchronized(this){if(ticket!=generation||!matches(id,session,reply)||!session.equals(connections.get(receiver)))return -11;}
            JSONObject result=reply.getJSONObject("result");
            if(!"battery_status".equals(result.getString("operation")))return -11;
            JSONObject battery=result.getJSONObject("data");
            byte[] response=XiaomiDeviceProfileCodec.response(copied,battery.getInt("level"),battery.getInt("plugged"));
            Context ctx=(Context)context.get(receiver);
            // Match SharedChannelImpl.buildIntent and its manifest-private native receiver.
            Intent intent=new Intent("com.miui.circulate.channel.action.RECEIVE_SHARE_CHANNEL")
                .setClassName("com.milink.service","com.milink.deviceprofile.service.SharedChannelReceiver")
                .addCategory("deviceprofile")
                .putExtra("com.miui.circulate.channel.extra.EXTRA_SHARE_CHANNEL_DEVICE_ID",id)
                .putExtra("com.miui.circulate.channel.extra.EXTRA_SHARE_CHANNEL_ID",-1)
                .putExtra("com.miui.circulate.channel.extra.EXTRA_SHARE_CHANNEL_DATA",response);
            synchronized(this){
                if(ticket!=generation||!matches(id,session,reply))return -11;
                ctx.sendBroadcast(intent);
            }
            log("deviceprofile_real_response_delivered");return 0;
        }catch(ReflectiveOperationException|org.json.JSONException|RuntimeException failure){log("deviceprofile_response_failed");return -11;}
    }
    private void add(Object receiver){
        if(closed||!allowed.getAsBoolean())return;
        receivers.removeIf(w->w.get()==null);
        if(receivers.stream().anyMatch(w->w.get()==receiver)||receivers.size()>=32)return;
        receivers.add(new WeakReference<>(receiver));if(currentDevice!=null)notify(receiver,found,currentDevice);
    }
    private void remove(Object receiver){
        if(currentDevice!=null)notify(receiver,lost,currentDevice);
        receivers.removeIf(w->w.get()==null||w.get()==receiver);
    }
    private void update(JSONObject projection){
        if(closed||!allowed.getAsBoolean())projection=null;
        Object next=null;
        if(projection!=null)try{
            Class<?> builder=type("com.xiaomi.continuity.netbus.DeviceInfo$Builder");Object b=builder.getConstructor().newInstance();
            builder.getMethod("setDeviceId",String.class).invoke(b,projection.getString("id"));
            builder.getMethod("setDeviceName",String.class).invoke(b,projection.getJSONObject("real").getString("name"));
            builder.getMethod("setDeviceType",int.class).invoke(b,projection.getInt("sdk_device_type"));
            next=builder.getMethod("build").invoke(b);
        }catch(ReflectiveOperationException|org.json.JSONException|RuntimeException failure){projection=null;log("projection_build_failed");}
        synchronized(this){generation++;connections.clear();current=null;}
        for(var w:receivers){Object r=w.get();if(r!=null&&currentDevice!=null)notify(r,lost,currentDevice);}
        current=projection;currentDevice=next;
        for(var w:receivers){Object r=w.get();if(r!=null&&next!=null)notify(r,found,next);}
        receivers.removeIf(w->w.get()==null);
    }
    private void notify(Object receiver,Method method,Object device){
        try{method.invoke(receiver,"00070A2A",device);log(method==found?"projection_published":"projection_withdrawn");}
        catch(ReflectiveOperationException|RuntimeException failure){log("projection_callback_failed");}
    }
    private void log(String text){module.log(Log.INFO,"QiangJian",text+" source=1");}
    void close(){
        closed=true;client.onProjection(null);
        synchronized(this){generation++;connections.clear();current=null;}
        main.post(()->{for(var w:receivers){Object r=w.get();if(r!=null&&currentDevice!=null)notify(r,lost,currentDevice);}receivers.clear();currentDevice=null;});
        for(HookHandle handle:handles)handle.unhook();handles.clear();
    }
}
