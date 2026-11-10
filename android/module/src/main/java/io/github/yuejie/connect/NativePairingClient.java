package io.github.yuejie.connect;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Controller only: devices and user actions remain in the OEM discovery/card UI. */
final class NativePairingClient implements Application.ActivityLifecycleCallbacks {
    private final BridgeClient bridge;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Consumer<JSONObject>> listeners=new CopyOnWriteArrayList<>();
    private JSONObject snapshot=new JSONObject();
    private long until;
    private long bootstrapAfter,nextBootstrap;
    private Activity entry;
    private boolean pending,starting;
    NativePairingClient(BridgeClient bridge){this.bridge=bridge;}
    void register(Application app){app.registerActivityLifecycleCallbacks(this);}
    BridgeClient.Subscription listen(Consumer<JSONObject> listener){listeners.add(listener);main.post(()->listener.accept(snapshot));return ()->listeners.remove(listener);}
    JSONObject candidate(){JSONArray peers=snapshot.optJSONArray("peers");return peers==null?null:peers.optJSONObject(0);}
    JSONObject state(){return snapshot.optJSONObject("pairing");}
    boolean owns(String id){JSONObject candidate=candidate();return id!=null&&candidate!=null&&id.equals(candidate.optString("id"));}
    void select(String id){
        if(!owns(id))return;
        try{bridge.pairing(new JSONObject().put("action","select").put("peer",id),this::publish);}catch(JSONException ignored){}
    }
    void cancel(String id,Consumer<JSONObject> completion){
        JSONObject state=state();
        try{if(state==null||!id.equals(state.optString("id"))||!NativePairingPolicy.session(state.optString("session"))){completion.accept(new JSONObject().put("status","error").put("code","stale_session"));return;}
            bridge.pairing(new JSONObject().put("action","disconnect").put("session",state.getString("session")),completion);
        }catch(JSONException ignored){}
    }
    void start(){long now=SystemClock.elapsedRealtime();until=now+120_000;bootstrapAfter=now+1500;starting=true;main.removeCallbacks(poll);main.post(poll);}
    void watch(){until=Long.MAX_VALUE;main.removeCallbacks(poll);main.post(poll);}
    void close(){until=0;entry=null;main.removeCallbacks(poll);listeners.clear();}
    private final Runnable poll=new Runnable(){public void run(){
        if(SystemClock.elapsedRealtime()>=until)return;
        if(!pending){pending=true;try{bridge.pairing(new JSONObject().put("action",starting?"start":"status"),value->{pending=false;
            if("ok".equals(value.optString("status"))){starting=false;publish(value);}
            else if(starting&&"service_unavailable".equals(value.optString("code")))bootstrap();
        });}catch(JSONException ignored){pending=false;}}
        main.postDelayed(this,750);
    }};
    private void bootstrap(){
        long now=SystemClock.elapsedRealtime();
        Activity activity=entry;
        if(activity==null||activity.isFinishing()||now<bootstrapAfter||now<nextBootstrap)return;
        nextBootstrap=now+15_000;
        bridge.retryBinding();
    }
    private void publish(JSONObject value){
        if(!"ok".equals(value.optString("status"))||!value.has("pairing"))return;
        if(snapshot.toString().equals(value.toString()))return;snapshot=value;
        for(Consumer<JSONObject> listener:listeners)listener.accept(value);
    }
    @Override public void onActivityResumed(Activity activity){
        if(java.util.Set.of("com.miui.circulate.world.CirculateWorldActivity","com.oplus.linker.synergy.ui.activity.ConnectPCSettingsActivity",
                "com.oplus.linker.plugin.devicediscovery.ui.NewDeviceDiscoveryActivity").contains(activity.getClass().getName())){
            entry=activity;start();
        }
    }
    @Override public void onActivityCreated(Activity activity,Bundle state){}
    @Override public void onActivityStarted(Activity activity){}
    @Override public void onActivityPaused(Activity activity){if(entry==activity)entry=null;}
    @Override public void onActivityStopped(Activity activity){}
    @Override public void onActivityDestroyed(Activity activity){}
    @Override public void onActivitySaveInstanceState(Activity activity,Bundle state){}
}
