package io.github.yuejie.connect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Debug-only, shell DUMP protected trigger of the genuine OEM DeviceProfileManager API. */
public final class XiaomiNativeProfileProbe {
    private static final AtomicBoolean running=new AtomicBoolean();
    private XiaomiNativeProfileProbe() {}
    public static void install(Context context,ClassLoader loader,BridgeClient client){
        context.registerReceiver(new BroadcastReceiver(){
            @Override public void onReceive(Context ctx,Intent intent){
                if(!running.compareAndSet(false,true))return;
                var projection=client.projection();
                if(projection==null){running.set(false);Log.i("YueJieXiaomiTest","peer_offline");return;}
                String id=projection.optString("id");
                new Thread(()->{
                    Object manager=null;
                    try{
                        Class<?> type=Class.forName("com.milink.deviceprofile.api.DeviceProfileManager",false,loader);
                        Class<?> callback=Class.forName("com.milink.deviceprofile.api.DeviceProfileManager$IProfileEventCallback",false,loader);
                        manager=type.getConstructor().newInstance();CountDownLatch done=new CountDownLatch(1);
                        Object listener=Proxy.newProxyInstance(loader,new Class<?>[]{callback},(proxy,method,args)->{
                            if(method.getDeclaringClass()==Object.class)return switch(method.getName()){
                                case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->"YJ native profile probe";};
                            if(method.getName().equals("onResponse")){
                                Object data=args[0].getClass().getMethod("getDeviceInfo").invoke(args[0]);
                                Object level=data.getClass().getMethod("getBattery").invoke(data);
                                Object plugged=data.getClass().getMethod("getChargingState").invoke(data);
                                Log.i("YueJieXiaomiTest","native_onResponse level="+level+" plugged="+plugged);
                            }else Log.i("YueJieXiaomiTest","native_"+method.getName());
                            done.countDown();return null;
                        });
                        type.getMethod("init",Context.class).invoke(manager,context);
                        type.getMethod("syncDeviceProfile",String.class,int.class,callback).invoke(manager,id,128,listener);
                        if(!done.await(12,TimeUnit.SECONDS))Log.i("YueJieXiaomiTest","native_timeout");
                    }catch(Exception failure){Log.i("YueJieXiaomiTest","probe_error "+failure.getClass().getSimpleName());}
                    finally{
                        if(manager!=null)try{manager.getClass().getMethod("unInit").invoke(manager);}catch(Exception ignored){}
                        running.set(false);
                    }
                },"yj-xiaomi-native-probe").start();
            }
        },new IntentFilter("io.github.yuejie.connect.XIAOMI_PROFILE_PROBE"),"android.permission.DUMP",null,Context.RECEIVER_EXPORTED);
        Log.i("YueJieXiaomiTest","probe_registered");
    }
}
