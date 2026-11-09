package io.github.yuejie.connect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import static io.github.yuejie.connect.NetworkShareSession.*;

/** Reuses the PhoneCard's original cellular controls in :ui; the owner remains in :core. */
final class XiaomiNetworkCardState implements AutoCloseable {
    private final BridgeClient client;
    private final BooleanSupplier allowed;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<Object,String> managers=new WeakHashMap<>();
    private final ArrayList<HookHandle> hooks=new ArrayList<>();
    private final Method callback;
    private final BroadcastReceiver receiver;
    private final BridgeClient.Subscription subscription;
    private volatile NetworkShareSurface.Snapshot snapshot;
    private volatile boolean closed;
    private final Runnable expire=new Runnable(){public void run(){
        if(closed)return;
        var value=snapshot;
        if(value!=null&&!value.live(SystemClock.elapsedRealtime())){snapshot=null;refresh();}
        main.postDelayed(this,1000);
    }};

    XiaomiNetworkCardState(XposedModule module,BridgeClient client,ClassLoader loader,BooleanSupplier allowed)
            throws ReflectiveOperationException {
        this.client=client;this.allowed=allowed;
        Class<?> manager=Class.forName("com.miui.circulate.api.protocol.synergy.manager.CellularSynergyManager",false,loader);
        callback=manager.getMethod("onCellularStateChange",String.class,int.class);
        receiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){
            if(closed)return;
            var previous=snapshot;
            try{
                JSONObject data=new JSONObject(intent.getStringExtra("state"));
                snapshot=data.optBoolean("eligible")?new NetworkShareSurface.Snapshot(data.getString("session"),
                    data.getString("id"),data.getString("name"),Phase.valueOf(data.getString("phase")),
                    data.optString("upstream").isEmpty()?null:Upstream.valueOf(data.getString("upstream")),
                    data.getBoolean("available"),data.getLong("observed_at")):null;
            }catch(Exception invalid){snapshot=null;}
            var next=snapshot;long now=SystemClock.elapsedRealtime();
            if(previous==null&&next==null)return;
            if(previous==null||next==null||!previous.id().equals(next.id())||!previous.session().equals(next.session())
                    ||previous.state(now)!=next.state(now))refresh();
        }};
        try{
            hooks.add(module.hook(manager.getMethod("getCellularTetherState",String.class)).setId("yj.network.card.state").intercept(chain->{
                String peer=(String)chain.getArg(0);
                if(!owns(peer))return chain.proceed();
                remember(chain.getThisObject(),peer);return state(peer);
            }));
            hooks.add(module.hook(manager.getMethod("isSynergy",String.class)).setId("yj.network.card.active").intercept(chain->{
                String peer=(String)chain.getArg(0);if(!owns(peer))return chain.proceed();
                int value=state(peer);return value==3||value==4;
            }));
            hooks.add(module.hook(callback).setId("yj.network.card.callback").intercept(chain->{
                String peer=(String)chain.getArg(0);if(!owns(peer))return chain.proceed();
                remember(chain.getThisObject(),peer);return chain.proceed(new Object[]{peer,state(peer)});
            }));
            for(String action:new String[]{"start","stop"}){
                String method="start".equals(action)?"connectCellularTether":"disconnectCellularTether";
                hooks.add(module.hook(manager.getMethod(method,String.class)).setId("yj.network.card."+action).intercept(chain->{
                    String peer=(String)chain.getArg(0);if(!owns(peer))return chain.proceed();
                    remember(chain.getThisObject(),peer);
                    var value=current(peer);
                    if(value!=null&&value.controls(peer,SystemClock.elapsedRealtime())){
                        client.browserRelay(new JSONObject().put("op","network_card_control").put("session",value.session())
                            .put("id",peer).put("action",action),reply->{
                                if(reply==null||!"ok".equals(reply.optString("status")))refresh();
                            });
                    }
                    return null;
                }));
            }
            client.context().registerReceiver(receiver,new IntentFilter(NetworkShareSurface.STATE),
                NetworkShareSurface.MODULE_PERMISSION,main,Context.RECEIVER_EXPORTED);
        }catch(ReflectiveOperationException|RuntimeException failure){for(var hook:hooks)hook.unhook();throw failure;}
        subscription=client.addProjectionListener(value->main.post(this::refresh));
        main.post(expire);
    }
    private boolean owns(String peer){
        JSONObject p=client.projection();
        return !closed&&allowed.getAsBoolean()&&p!=null&&peer!=null&&peer.equals(p.optString("id"));
    }
    private NetworkShareSurface.Snapshot current(String peer){
        var value=snapshot;JSONObject p=client.projection();
        if(!owns(peer)||value==null)return null;
        var ops=p.optJSONArray("operations");
        boolean granted=false;
        if(ops!=null)for(int i=0;i<ops.length();i++)granted|="network_share".equals(ops.optString(i));
        return value.matches(peer,p.optString("session_id"),p.optString("control_transport"),granted)?value:null;
    }
    private int state(String peer){var value=current(peer);return value==null?0:value.state(SystemClock.elapsedRealtime());}
    private void remember(Object manager,String peer){synchronized(managers){managers.put(manager,peer);}}
    private void refresh(){
        if(closed)return;
        ArrayList<Map.Entry<Object,String>> copy;
        synchronized(managers){copy=new ArrayList<>(managers.entrySet());}
        for(var entry:copy)try{callback.invoke(entry.getKey(),entry.getValue(),state(entry.getValue()));}
        catch(ReflectiveOperationException|RuntimeException failure){android.util.Log.w("YueJieNetwork","card_refresh_failed");}
    }
    @Override public void close(){
        closed=true;subscription.close();main.removeCallbacks(expire);
        client.context().unregisterReceiver(receiver);
        for(var hook:hooks)hook.unhook();hooks.clear();
        synchronized(managers){managers.clear();}snapshot=null;
    }
}
