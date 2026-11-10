package io.github.yuejie.connect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import org.json.JSONObject;
import static io.github.yuejie.connect.NetworkShareSession.*;

/** HyperOS control-center adapter. Keeps the OEM tile, callbacks and thermal/airplane checks. */
final class XiaomiControlCenterNetwork {
    private static final String OWNER="com.android.systemui.controlcenter.policy.ShareNetworkControllerImpl";
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<Object> controllers=Collections.newSetFromMap(new WeakHashMap<>());
    private volatile NetworkShareSurface.Snapshot snapshot;
    private final io.github.libxposed.api.XposedInterface.CtorInvoker<Object> cellular;
    private final Class<?> cellularClass;
    private final Field id,name,state;
    private final Method callback,getInfo;
    private final ArrayList<io.github.libxposed.api.XposedInterface.HookHandle> hooks=new ArrayList<>();

    XiaomiControlCenterNetwork(XposedModule module,Context context,ClassLoader loader)throws Exception{
        this.context=context;
        Class<?> owner=Class.forName(OWNER,false,loader);
        Class<?> value=Class.forName("com.android.systemui.controlcenter.policy.ShareNetworkController$Cellular",false,loader);
        // Android 17's optimized DEX has removed this data holder's constructor.
        // Allocate through libxposed's superclass constructor invoker, as its native code does.
        cellularClass=value;cellular=module.getInvoker(Object.class.getDeclaredConstructor());
        id=value.getDeclaredField("deviceId");id.setAccessible(true);
        name=value.getDeclaredField("deviceName");name.setAccessible(true);
        state=value.getDeclaredField("state");state.setAccessible(true);
        getInfo=owner.getDeclaredMethod("getCelluarTetherDeviceInfo");getInfo.setAccessible(true);
        callback=owner.getDeclaredMethod("access$onCellularTetherStateUpdateCallback",owner,value);callback.setAccessible(true);
        try{
            for(Constructor<?> constructor:owner.getDeclaredConstructors())hooks.add(module.hook(constructor)
                .setId("yj.systemui.network.owner").intercept(chain->{Object result=chain.proceed();remember(chain.getThisObject());return result;}));
            hooks.add(module.hook(owner.getDeclaredMethod("supportCellular")).setId("yj.systemui.network.support")
                .intercept(chain->current()!=null?true:chain.proceed()));
            hooks.add(module.hook(getInfo).setId("yj.systemui.network.info").intercept(chain->{
                remember(chain.getThisObject());Object own=model();return own!=null?own:chain.proceed();
            }));
            hooks.add(module.hook(callback).setId("yj.systemui.network.callback").intercept(chain->{
                remember(chain.getArg(0));Object own=model();
                return own==null?chain.proceed():chain.proceed(new Object[]{chain.getArg(0),own});
            }));
            hookAction(module,loader,OWNER+"$connectCelluarTether$1",0,"start");
            hookAction(module,loader,OWNER+"$connectHotspot$1",1,"stop");
            // These OEM callers can contain AOT-inlined capability/get-info calls.
            Class<?> tile=Class.forName("com.android.systemui.qs.tiles.MiuiCellularTile",false,loader);
            module.deoptimize(tile.getDeclaredMethod("handleClick",Class.forName("com.android.systemui.animation.Expandable",false,loader)));
            module.deoptimize(tile.getDeclaredMethod("handleUpdateState",Class.forName("com.android.systemui.plugins.qs.QSTile$State",false,loader),Object.class));

        }catch(Exception failure){for(var hook:hooks)hook.unhook();throw failure;}
        context.registerReceiver(new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent intent){
                if(!NativeBridgeHost.hostCaller(c.getPackageManager(),getSentFromUid()))return;
                try{
                    JSONObject data=new JSONObject(intent.getStringExtra("state"));
                    NetworkShareSurface.Snapshot next=null;
                    if(data.optBoolean("eligible"))next=new NetworkShareSurface.Snapshot(data.getString("session"),
                        data.getString("id"),data.getString("name"),Phase.valueOf(data.getString("phase")),
                        data.optString("upstream").isEmpty()?null:Upstream.valueOf(data.getString("upstream")),
                        data.getBoolean("available"),data.getLong("observed_at"));
                    var previous=snapshot;snapshot=next;
                    if(previous==null&&next==null)return;
                    // Heartbeats extend freshness without continuously rebinding the native tile.
                    if(previous==null||next==null||previous.state(SystemClock.elapsedRealtime())!=next.state(SystemClock.elapsedRealtime())
                        ||!previous.id().equals(next.id())||!previous.name().equals(next.name()))refresh();
                }catch(Exception invalid){snapshot=null;refresh();}
            }
        },new IntentFilter(NetworkShareSurface.STATE),null,main,Context.RECEIVER_EXPORTED);
        main.post(new Runnable(){public void run(){
            var value=snapshot;if(value!=null&&!value.live(SystemClock.elapsedRealtime())){snapshot=null;refresh();}
            main.postDelayed(this,2000);
        }});
        android.util.Log.i("YueJieNetworkUi","control_center_installed");
    }
    private void remember(Object controller){synchronized(controllers){controllers.add(controller);}}
    private NetworkShareSurface.Snapshot current(){
        var value=snapshot;return value!=null&&value.state(SystemClock.elapsedRealtime())!=0?value:null;
    }
    private Object model()throws ReflectiveOperationException{
        var value=current();if(value==null)return null;
        Object result=cellular.newInstanceSpecial(cellularClass);id.set(result,value.id());name.set(result,value.name());
        state.setInt(result,value.state(SystemClock.elapsedRealtime()));return result;
    }
    private void refresh(){
        ArrayList<Object> copy;synchronized(controllers){copy=new ArrayList<>(controllers);}
        for(Object controller:copy)try{
            Field executor=controller.getClass().getDeclaredField("bgExecutor");executor.setAccessible(true);
            ((Executor)executor.get(controller)).execute(()->{
                try{callback.invoke(null,controller,getInfo.invoke(controller));}
                catch(Exception failure){android.util.Log.w("YueJieNetworkUi","native_refresh_failed "+failure.getClass().getSimpleName());}
            });
        }catch(Exception failure){android.util.Log.w("YueJieNetworkUi","native_executor_unavailable");}
    }
    private void hookAction(XposedModule module,ClassLoader loader,String type,int variant,String action)throws Exception{
        Class<?> task=Class.forName(type,false,loader);
        Field kind=task.getDeclaredField("$r8$classId"),device=task.getDeclaredField("$deviceId");
        kind.setAccessible(true);device.setAccessible(true);
        hooks.add(module.hook(task.getDeclaredMethod("run")).setId("yj.systemui.network."+action).intercept(chain->{
            var value=current();
            if(value==null||kind.getInt(chain.getThisObject())!=variant
                ||!value.controls((String)device.get(chain.getThisObject()),SystemClock.elapsedRealtime()))return chain.proceed();
            context.sendBroadcast(new Intent(NetworkShareSurface.CONTROL).setPackage(CallerPolicy.XIAOMI)
                .putExtra("session",value.session()).putExtra("action",action));
            android.util.Log.i("YueJieNetworkUi","native_tile_action="+action);
            return null;
        }));
    }
}
