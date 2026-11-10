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
    private boolean receiverRegistered;
    private final Map<String,java.lang.ref.WeakReference<android.view.View>> cards=new java.util.HashMap<>();
    private String connecting;
    private android.app.Dialog chooser;
    private final ClassLoader loader;
    private final Runnable expire=new Runnable(){public void run(){
        if(closed)return;
        var value=snapshot;
        if(value!=null&&!value.live(SystemClock.elapsedRealtime())){snapshot=null;refresh();}
        main.postDelayed(this,1000);
    }};

    XiaomiNetworkCardState(XposedModule module,BridgeClient client,ClassLoader loader,BooleanSupplier allowed)
            throws ReflectiveOperationException {
        this.client=client;this.allowed=allowed;this.loader=loader;
        Class<?> manager=Class.forName("com.miui.circulate.api.protocol.synergy.manager.CellularSynergyManager",false,loader);
        callback=manager.getMethod("onCellularStateChange",String.class,int.class);
        receiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){
            if(closed||!NativeBridgeHost.hostCaller(context.getPackageManager(),getSentFromUid()))return;
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
            Class<?> card=Class.forName("com.miui.circulate.world.sticker.blecard.PhoneCard",false,loader);
            Method info=card.getMethod("getMInfo");
            hooks.add(module.hook(card.getMethod("J2")).setId("yj.network.card.owner").intercept(chain->{
                Object result=chain.proceed();Object device=info.invoke(chain.getThisObject());
                String id=(String)device.getClass().getField("id").get(device);
                if(owns(id))cards.put(id,new java.lang.ref.WeakReference<>((android.view.View)chain.getThisObject()));
                return result;
            }));
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
            // The OEM's small controller forwarding methods can be AOT-inlined past the manager hook.
            Class<?> controller=Class.forName("com.miui.circulate.api.protocol.synergy.SynergyController",false,loader);
            Class<?> plugin=Class.forName("dg.d",false,loader);
            module.deoptimize(plugin.getMethod("T",String.class));
            module.deoptimize(plugin.getMethod("f0",String.class));
            for(String action:new String[]{"start","stop"}){
                String method="start".equals(action)?"connectCellularTether":"disconnectCellularTether";
                module.deoptimize(controller.getMethod(method,String.class));
                hooks.add(module.hook(manager.getMethod(method,String.class)).setId("yj.network.card."+action).intercept(chain->{
                    String peer=(String)chain.getArg(0);if(!owns(peer))return chain.proceed();
                    remember(chain.getThisObject(),peer);
                    if("start".equals(action)&&wifiReady(peer)) {main.post(()->connectBluetooth(peer));return null;}
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
        }catch(ReflectiveOperationException|RuntimeException failure){for(var hook:hooks)hook.unhook();throw failure;}
        subscription=client.addProjectionListener(value->main.post(this::refresh));
        // Package hooks install before Application.attach provides the Context.
        main.post(()->{
            if(closed)return;
            try{
                client.context().registerReceiver(receiver,new IntentFilter(NetworkShareSurface.STATE),
                    null,main,Context.RECEIVER_EXPORTED);
                receiverRegistered=true;
            }catch(RuntimeException failure){android.util.Log.e("YueJieNetwork","card_receiver_unavailable",failure);}
        });
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
    private boolean wifiReady(String peer){
        JSONObject p=client.projection();
        if(!owns(peer)||!"wifi".equals(p.optString("control_transport")))return false;
        var ops=p.optJSONArray("operations");
        if(ops!=null)for(int i=0;i<ops.length();i++)if("network_share".equals(ops.optString(i)))return true;
        return false;
    }
    private int state(String peer){
        if(wifiReady(peer))return peer.equals(connecting)?2:1;
        var value=current(peer);return value==null?0:value.state(SystemClock.elapsedRealtime());
    }
    private void connectBluetooth(String peer){
        if(!wifiReady(peer)||connecting!=null)return;
        android.util.Log.i("YueJieNetwork","native_card_handover_requested");
        JSONObject p=client.projection();String session=p.optString("session_id");connecting=peer;refresh();
        try{
            client.browserRelay(new JSONObject().put("op","network_bluetooth_devices").put("id",peer).put("session",session),reply->{
                if(!same(peer,session)){finishConnect(null);return;}
                var devices=reply==null?null:reply.optJSONArray("devices");
                android.util.Log.i("YueJieNetwork","native_card_bluetooth_result="+(reply==null?"unavailable":reply.optString("status"))+" count="+(devices==null?0:devices.length()));
                if(devices==null||devices.length()==0){finishConnect("请先在系统蓝牙设置中配对手机和平板，并开启蓝牙");return;}
                var preferences=client.context().getSharedPreferences("yj-network-card",Context.MODE_PRIVATE);
                String saved=preferences.getString(peer,"");
                for(int i=0;i<devices.length();i++)if(saved.equals(devices.optJSONObject(i).optString("address"))){prepare(peer,session,saved);return;}
                if(devices.length()==1){prepare(peer,session,devices.optJSONObject(0).optString("address"));return;}
                try{
                    var reference=cards.get(peer);var view=reference==null?null:reference.get();
                    if(view==null||!view.isAttachedToWindow()) {android.util.Log.w("YueJieNetwork","native_card_view_unavailable");finishConnect(null);return;}
                    Class<?> builder=Class.forName("miuix.appcompat.app.u$a",false,loader);
                    Object dialogBuilder=builder.getConstructor(Context.class).newInstance(view.getContext());
                    CharSequence[] names=new CharSequence[devices.length()];
                    for(int i=0;i<names.length;i++)names[i]=devices.getJSONObject(i).getString("name");
                    builder.getMethod("m",CharSequence[].class,android.content.DialogInterface.OnClickListener.class).invoke(dialogBuilder,names,
                        (android.content.DialogInterface.OnClickListener)(dialog,index)->prepare(peer,session,devices.optJSONObject(index).optString("address")));
                    chooser=(android.app.Dialog)builder.getMethod("a").invoke(dialogBuilder);
                    chooser.setTitle("选择要共享网络的手机");chooser.setOnCancelListener(dialog->finishConnect(null));chooser.show();
                }catch(Exception failure){finishConnect("无法打开蓝牙设备选择，请重新打开卡片");}
            });
        }catch(Exception failure){finishConnect("网络共享暂不可用");}
    }
    private boolean same(String peer,String session){JSONObject p=client.projection();return wifiReady(peer)&&session.equals(p.optString("session_id"));}
    private void prepare(String peer,String session,String address){
        if(!same(peer,session)){finishConnect(null);return;}
        try{
            String request=java.util.UUID.randomUUID().toString();
            client.request(peer,session,"network_share",request,new JSONObject().put("action","prepare_control"),reply->{
                if(!same(peer,session)){finishConnect(null);return;}
                try{
                    if(reply==null||!"ok".equals(reply.optString("status"))||!session.equals(reply.optString("session_id"))
                        ||!request.equals(reply.optString("request_id")))throw new IllegalStateException();
                    JSONObject result=reply.getJSONObject("result");
                    if(!"network_share".equals(result.optString("operation")))throw new IllegalStateException();
                    String ticket=result.getJSONObject("data").getString("ticket");
                    client.browserRelay(new JSONObject().put("op","network_bluetooth_connect").put("id",peer).put("session",session)
                        .put("ticket",ticket).put("address",address),connected->{
                            if(connected==null||!"ok".equals(connected.optString("status"))){finishConnect("蓝牙连接失败，请确认两端蓝牙已开启");return;}
                            client.context().getSharedPreferences("yj-network-card",Context.MODE_PRIVATE).edit().putString(peer,address).apply();
                            main.postDelayed(()->{if(peer.equals(connecting))finishConnect(null);},60_000);
                        });
                }catch(Exception failure){finishConnect("手机未准备好网络共享，请确认两端已更新并授权网络共享");}
            });
        }catch(Exception failure){finishConnect("网络共享暂不可用");}
    }
    private void finishConnect(String message){
        connecting=null;refresh();
        if(message!=null&&!closed)android.widget.Toast.makeText(client.context(),message,android.widget.Toast.LENGTH_LONG).show();
    }
    private void remember(Object manager,String peer){synchronized(managers){managers.put(manager,peer);}}
    private void refresh(){
        if(closed)return;
        if(connecting!=null&&!wifiReady(connecting)){connecting=null;if(chooser!=null){chooser.dismiss();chooser=null;}}
        ArrayList<Map.Entry<Object,String>> copy;
        synchronized(managers){copy=new ArrayList<>(managers.entrySet());}
        for(var entry:copy)try{callback.invoke(entry.getKey(),entry.getValue(),state(entry.getValue()));}
        catch(ReflectiveOperationException|RuntimeException failure){android.util.Log.w("YueJieNetwork","card_refresh_failed");}
    }
    @Override public void close(){
        closed=true;subscription.close();main.removeCallbacks(expire);
        if(chooser!=null)chooser.dismiss();cards.clear();
        if(receiverRegistered)client.context().unregisterReceiver(receiver);
        for(var hook:hooks)hook.unhook();hooks.clear();
        synchronized(managers){managers.clear();}snapshot=null;
    }
}
