package io.github.yuejie.connect;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Proxy;
import org.json.JSONObject;

/** Authenticated remote requests enter the OEM SDK, which owns the capture consent UI. */
final class ScreenCastLinkerRuntime implements BridgeClient.VendorExecutor {
    private final BridgeClient client;
    private final ClassLoader loader;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object sdk;
    private final Class<?> sdkType,deviceType,callbackType;
    private final MediaControlRuntime media;
    private boolean ready;
    private long stream;
    private String session;
    private Object requestedPeer;
    private MediaControlCastRegistry.Lease mediaLease;
    ScreenCastLinkerRuntime(BridgeClient client,ClassLoader loader)throws Exception {
        this.client=client;this.loader=loader;
        sdkType=Class.forName("com.oplus.cast.service.sdk.api.HeyCastClientManager",false,loader);
        deviceType=Class.forName("com.oplus.cast.service.sdk.config.CastDevice",false,loader);
        callbackType=Class.forName("com.oplus.cast.service.sdk.api.ScreenRecordAuthorizeCallback",false,loader);
        Class<?> delegate=Class.forName("com.oplus.cast.service.sdk.api.HeyCastClientManagerDelegate",false,loader);
        sdk=sdkType.getConstructor(android.content.Context.class).newInstance(client.context());
        sdkType.getMethod("registerDelegate",delegate).invoke(sdk,Proxy.newProxyInstance(loader,new Class<?>[]{delegate},(p,m,a)->{
            if(m.getName().equals("onOpen")){sdkType.getMethod("initSdk",int.class).invoke(sdk,4);ready=true;ScreenCastLog.i("YueJieScreen","sdk_ready");}
            if(m.getName().equals("onClose"))ready=false;
            return objectMethod(p,m.getName(),a);
        }));
        sdkType.getMethod("open").invoke(sdk);
        client.registerExecutor("screen_request",this);
        // Linker holds MEDIA_CONTENT_CONTROL. The capture process attests the rendered stream.
        media=new MediaControlRuntime(client,new MediaControlEndpoint(client.context(),CallerPolicy.OPLUS));
        main.post(new Runnable(){public void run(){refreshMedia();main.postDelayed(this,500);}});
    }
    private boolean rendered(MediaControlNativeRoute.Scope scope){
        try{
            JSONObject result=client.nativeScreenRequest(new JSONObject().put("op","screen_request_media")
                .put("session_id",scope.sessionId()).put("stream_id",scope.streamId()));
            return "ok".equals(result.optString("status"))&&result.optBoolean("rendered");
        }catch(org.json.JSONException invalid){return false;}
    }
    private void refreshMedia(){
        if(mediaLease!=null){
            if(mediaLease.current())return;
            clearMedia();
        }
        JSONObject projection=client.projection();
        Object peer=requestedPeer;
        if(peer==null||projection==null||!projection.optString("session_id").equals(session)
                ||!client.hasLocalGrant(MediaControlTasks.OPERATION))return;
        String id=projection.optString("id");
        var scope=new MediaControlNativeRoute.Scope(id,session,id,stream,0);
        if(!rendered(scope))return;
        try{
            String target=MediaControlEndpoint.ACTIVE_SESSION;
            mediaLease=media.bind(scope,peer,sdk,target,()->new MediaControlCastRegistry.NativeState(
                scope,peer,sdk,rendered(scope),client.hasLocalGrant("screen_video"),false,true,target));
            media.refresh();
            ScreenCastLog.i("YueJieMedia","source_control_ready");
        }catch(IllegalStateException unavailable){
            ScreenCastLog.w("YueJieMedia","source_control_unavailable "+unavailable.getMessage());
        }
    }
    private void clearMedia(){
        if(mediaLease!=null){mediaLease.close();mediaLease=null;media.refresh();}
    }
    static Object objectMethod(Object proxy,String name,Object[] args){
        return switch(name){case "toString"->"YJ native screen callback";case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->null;};
    }
    public void execute(JSONObject task,BridgeClient.TaskCompletion completion){
        main.post(()->{
            if(ready){start(task,completion);return;}
            // ColorOS can miss the first callback when Linker and Cast start together.
            try{sdkType.getMethod("open").invoke(sdk);}
            catch(ReflectiveOperationException unavailable){completion.fail("native_unavailable");return;}
            main.postDelayed(()->start(task,completion),750);
        });
    }
    private void start(JSONObject task,BridgeClient.TaskCompletion completion){
        try{
            JSONObject p=client.projection();
            if(!ready||p==null||android.os.SystemClock.elapsedRealtime()>=task.optLong("deadline_elapsed_ms")
                ||!task.getString("session_id").equals(p.getString("session_id"))
                ||!task.getString("projection_id").equals(p.getString("id"))||!client.hasLocalGrant("screen_video"))throw new IllegalStateException("screen_request_unavailable");
            long next=task.getJSONObject("payload").getLong("stream_id");
            if(next==stream&&p.getString("session_id").equals(session))throw new IllegalStateException("screen_scope_reused");
            clearMedia();requestedPeer=null;stream=next;session=p.getString("session_id");
            String id=p.getString("id");
            JSONObject proof=client.nativeScreenRequest(new JSONObject().put("op","screen_request_issue").put("session_id",session)
                .put("projection_id",id).put("stream_id",stream).put("native_uid",id).put("device_type","SYNERGY_PAD"));
            if(!"ok".equals(proof.optString("status")))throw new IllegalStateException(proof.optString("code"));
            Object device=deviceType.getConstructor().newInstance();
            deviceType.getMethod("setUid",String.class).invoke(device,id);
            deviceType.getMethod("setDeviceType",String.class).invoke(device,"SYNERGY_PAD");
            deviceType.getMethod("setDeviceName",String.class).invoke(device,p.getJSONObject("real").optString("name","Xiaomi Pad"));
            deviceType.getMethod("setSupportMirror",boolean.class).invoke(device,true);
            Bundle extras=new Bundle();extras.putString(OplusScreenLinkerAdapter.REQUEST_PROOF_EXTRA,proof.getString("proof"));
            Object callback=Proxy.newProxyInstance(loader,new Class<?>[]{callbackType},(o,m,a)->{
                ScreenCastLog.i("YueJieScreen","native_consent_"+m.getName());return objectMethod(o,m.getName(),a);
            });
            Object result=sdkType.getMethod("startMirror",deviceType,callbackType,Bundle.class).invoke(sdk,device,callback,extras);
            if(!(result instanceof Integer code)||code!=0)throw new IllegalStateException("native_start_"+result);
            requestedPeer=device;
            completion.success(new JSONObject().put("stream_id",stream).put("accepted",true));
        }catch(Exception error){ScreenCastLog.e("YueJieScreen","request_failed",error);completion.fail("native_unavailable");}
    }
    public void cancel(String session,String request){}
    public void sessionChanged(){requestedPeer=null;session=null;stream=0;clearMedia();}
}
