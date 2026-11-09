package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Native :ui discovery facade for the sampled Circulate client, separate from :core channels. */
final class XiaomiCirculateAdapter {
    private static final int PROTOCOL=196608; // Sample's Circulate device grouping, not a wire opcode.
    private final XposedModule module;
    private final BridgeClient client;
    private final ClassLoader loader;
    private final BooleanSupplier allowed;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<HookHandle> hooks=new ArrayList<>();
    private final List<Discovery> discoveries=new ArrayList<>();
    private final List<WeakReference<Object>> openCards=new ArrayList<>();
    private final Set<String> registered=new LinkedHashSet<>();
    private volatile JSONObject projection;
    private volatile boolean closed;
    private Class<?> deviceType,serviceType,builderType;
    private Field idField,callbackField,discoveringField,requestedServiceField,protocolField;
    private Method found,lost,circulateFailure,closeCard,available;
    private XiaomiFinderUiSupport finderUi;
    private XiaomiScreenCardState screenCard;
    private XiaomiNetworkCardState networkCard;
    private BridgeClient.Subscription pairingSubscription;
    private static final class Discovery {
        final WeakReference<Object> owner;
        Object device,service;
        Discovery(Object owner){this.owner=new WeakReference<>(owner);}
    }
    XiaomiCirculateAdapter(XposedModule module,BridgeClient client,ClassLoader loader,BooleanSupplier allowed){
        this.module=module;this.client=client;this.loader=loader;this.allowed=allowed;
    }
    private Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,false,loader);}
    void install()throws ReflectiveOperationException{
        try{
            Class<?> owner=type("com.miui.circulate.api.protocol.miuiplus.MiuiPlusClient");
            Class<?> base=type("com.miui.circulate.api.protocol.impl.b");
            Class<?> callback=type("sc.a");
            deviceType=type("com.miui.circulate.api.service.CirculateDeviceInfo");
            serviceType=type("com.miui.circulate.api.service.CirculateServiceInfo");
            builderType=type("com.miui.circulate.api.bean.ExtraBundle$b");
            requestedServiceField=type("com.miui.circulate.api.bean.CirculateParam").getField("circulateServiceInfo");
            protocolField=serviceType.getField("protocolType");
            idField=deviceType.getField("id");callbackField=base.getDeclaredField("mCallback");callbackField.setAccessible(true);
            discoveringField=owner.getDeclaredField("mDiscovery");discoveringField.setAccessible(true);
            available=base.getMethod("isAvailable");
            found=callback.getMethod("e",int.class,deviceType,serviceType);
            lost=callback.getMethod("f",int.class,deviceType,serviceType);
            circulateFailure=type("com.miui.circulate.api.protocol.impl.a").getDeclaredMethod("circulateResult",callback,int.class,int.class,Array.newInstance(deviceType,0).getClass());
            circulateFailure.setAccessible(true);
            hooks.add(module.hook(owner.getDeclaredMethod("startDiscovery",type("dd.a"),Executor.class)).setId("yj.xiaomi.ui.discovery").intercept(chain->{
                Object result=chain.proceed();Object receiver=chain.getThisObject();
                if(allowed.getAsBoolean())main.post(()->start(receiver));return result;
            }));
            hooks.add(module.hook(owner.getDeclaredMethod("stopDiscovery",type("dd.a"),boolean.class)).setId("yj.xiaomi.ui.stop").intercept(chain->{
                Object result=chain.proceed();Object receiver=chain.getThisObject();main.post(()->stop(receiver));return result;
            }));
            for(String method:new String[]{"release","unRegisterListener"}){
                hooks.add(module.hook(owner.getDeclaredMethod(method)).setId("yj.xiaomi.ui."+method).intercept(chain->{
                    Object receiver=chain.getThisObject();main.post(()->stop(receiver));return chain.proceed();
                }));
            }
            hooks.add(module.hook(owner.getDeclaredMethod("circulateService",List.class,List.class,type("com.miui.circulate.api.bean.CirculateParam")))
                .setId("yj.xiaomi.ui.reject_unimplemented").intercept(chain->{
                    @SuppressWarnings("unchecked") List<Object> sources=(List<Object>)chain.getArg(0);
                    @SuppressWarnings("unchecked") List<Object> targets=(List<Object>)chain.getArg(1);
                    if(targets==null)return chain.proceed();
                    var selected=XiaomiCirculateSelection.plan(sources,targets,this::registered);
                    if(!selected.intercepted())return chain.proceed();
                    Object param=chain.getArg(2),requested=param==null?null:requestedServiceField.get(param);
                    int protocol=requested==null?PROTOCOL:protocolField.getInt(requested);
                    Object array=Array.newInstance(deviceType,selected.rejected().size());
                    for(int i=0;i<selected.rejected().size();i++)Array.set(array,i,selected.rejected().get(i));
                    circulateFailure.invoke(chain.getThisObject(),callbackField.get(chain.getThisObject()),protocol,-3,array);
                    log("ui_operation_unsupported");
                    return selected.nativeTargets().isEmpty()?null:chain.proceed(new Object[]{sources,selected.nativeTargets(),param});
                }));
            installCardLifetime();
            screenCard=new XiaomiScreenCardState(module,client,loader,allowed);
            finderUi=new XiaomiFinderUiSupport(module,client,loader,allowed);
            networkCard=new XiaomiNetworkCardState(module,client,loader,allowed);
            client.onProjection(this::update);log("xiaomi_ui_adapter_installed");
            pairingSubscription=client.nativePairing().listen(value->{if(projection==null){for(Discovery d:discoveries)withdraw(d);for(Discovery d:discoveries)publish(d);}});
        }catch(ReflectiveOperationException|RuntimeException error){close();throw error;}
    }
    private boolean registered(Object device){
        if(closed||!allowed.getAsBoolean()||device==null)return false;
        try{synchronized(registered){return registered.contains((String)idField.get(device));}}
        catch(IllegalAccessException error){return false;}
    }
    private void installCardLifetime()throws ReflectiveOperationException{
        Class<?> mainCard=type("com.miui.circulate.world.sticker.MainCardView");
        closeCard=mainCard.getMethod("p",boolean.class);
        hooks.add(module.hook(mainCard.getMethod("k",deviceType,String.class,String.class,type("pf.a"),
            type("com.miui.circulate.world.base.h"),type("gf.g"))).setId("yj.xiaomi.ui.card_lifetime").intercept(chain->{
                String requested=(String)idField.get(chain.getArg(0));
                if(projection==null&&client.nativePairing().owns(requested)){main.post(()->client.nativePairing().select(requested));return null;}
                Object result=chain.proceed();Object view=chain.getThisObject();boolean owned=registered(chain.getArg(0));
                JSONObject ticket=projection;
                String selectedId=owned?(String)idField.get(chain.getArg(0)):null;
                main.post(()->{
                    openCards.removeIf(w->w.get()==null||w.get()==view);
                    if(!owned)return;
                    if(ticket==null||!selectedId.equals(ticket.optString("id"))||ticket!=projection||closed||!allowed.getAsBoolean()){
                        try{closeCard.invoke(view,false);}catch(ReflectiveOperationException|RuntimeException error){log("ui_card_close_failed");}
                    }else if(openCards.size()<16)openCards.add(new WeakReference<>(view));
                });return result;
            }));
        // Let PhoneCard/BleView initialize and render their original capability controls.
        // H2/J2 and the native layout/status callbacks also own the original click listeners;
        // blocking them or hiding their container leaves a connected phone card empty.
    }
    private boolean finderAllowed(){JSONObject p=projection;if(p==null)return false;var operations=p.optJSONArray("operations");if(operations!=null)for(int i=0;i<operations.length();i++)if("device_finder".equals(operations.optString(i)))return true;return false;}
    private void start(Object owner){
        if(closed||!allowed.getAsBoolean()||!discovering(owner))return;
        discoveries.removeIf(d->d.owner.get()==null);
        for(Discovery d:discoveries)if(d.owner.get()==owner){if(d.device==null)publish(d);return;}
        if(discoveries.size()>=16)return;
        Discovery d=new Discovery(owner);discoveries.add(d);publish(d);
    }
    private void stop(Object owner){
        for(var iterator=discoveries.iterator();iterator.hasNext();){Discovery d=iterator.next();if(d.owner.get()==null||d.owner.get()==owner){withdraw(d);iterator.remove();}}
    }
    private void update(JSONObject value){
        boolean hadFinder=finderAllowed();
        String prior=projection==null?null:projection.optString("session_id");
        String next=value==null?null:value.optString("session_id");
        if(!java.util.Objects.equals(prior,next))closeCards();
        for(Discovery d:discoveries)withdraw(d);
        projection=!closed&&allowed.getAsBoolean()?value:null;
        if(hadFinder&&!finderAllowed())closeCards();
        for(Discovery d:discoveries)publish(d);
    }
    private void publish(Discovery d){
        JSONObject p=projection==null?client.nativePairing().candidate():projection;Object owner=d.owner.get();
        if(p==null||owner==null||closed||!allowed.getAsBoolean()||!discovering(owner))return;
        try{
            boolean unpaired=p.optBoolean("unpaired");
            if((!unpaired&&!p.getString("session_id").matches("[0-9a-f]{64}"))||!"hyper_os4".equals(p.getString("target_sdk")))return;
            JSONObject real=p.getJSONObject("real");
            if(!"phone".equals(real.getString("kind")))return;
            boolean profile=false;var operations=p.getJSONArray("operations");
            for(int i=0;i<operations.length();i++)if("device_profile".equals(operations.getString(i)))profile=true;
            if(!profile&&!unpaired)return;
            Object callback=callbackField.get(owner);if(callback==null)return;
            Object device=deviceType.getConstructor().newInstance();String id=p.getString("id");
            idField.set(device,id);deviceType.getField("idHash").set(device,id);
            deviceType.getField("devicesName").set(device,real.getString("name"));
            deviceType.getField("devicesType").set(device,"AndroidPhone");
            Object properties=builderType.getConstructor().newInstance();
            builderType.getMethod("g",String.class,String.class).invoke(properties,"device_model",real.getString("model"));
            builderType.getMethod("g",String.class,String.class).invoke(properties,"rom_version",real.getString("os_build"));
            for(String flag:new String[]{"is_lyra","hand_off_switch","is_support_sub_screen"})
                builderType.getMethod("c",String.class,boolean.class).invoke(properties,flag,false);
            builderType.getMethod("e",String.class,int.class).invoke(properties,"mirror_status",0);
            builderType.getMethod("e",String.class,int.class).invoke(properties,"support_enable_mirror",0);
            deviceType.getField("deviceProperties").set(device,builderType.getMethod("a").invoke(properties));
            Object service=serviceType.getMethod("build",int.class).invoke(null,PROTOCOL);
            serviceType.getField("deviceId").set(service,id);
            serviceType.getField("connectState").setInt(service,0); // Never claim a Mirror/application session.
            @SuppressWarnings("unchecked") Set<Object> services=(Set<Object>)deviceType.getField("circulateServices").get(device);
            services.add(service);
            synchronized(registered){if(!registered.contains(id)&&registered.size()>=64)return;registered.add(id);}
            d.device=device;d.service=service;
            found.invoke(callback,PROTOCOL,device,service);log("ui_peer_published");
        }catch(ReflectiveOperationException|org.json.JSONException|RuntimeException error){withdraw(d);log("ui_publish_failed "+error.getClass().getSimpleName());}
    }
    private void withdraw(Discovery d){
        if(d.device==null)return;
        try{Object owner=d.owner.get();if(owner!=null){Object callback=callbackField.get(owner);if(callback!=null)lost.invoke(callback,PROTOCOL,d.device,d.service);}}
        catch(ReflectiveOperationException|RuntimeException error){log("ui_withdraw_failed");}
        d.device=null;d.service=null;log("ui_peer_withdrawn");
    }
    private void log(String message){module.log(android.util.Log.INFO,"YueJie",message+" source=1");}
    private boolean discovering(Object owner){
        try{return Boolean.TRUE.equals(available.invoke(owner))&&((java.util.concurrent.atomic.AtomicBoolean)discoveringField.get(owner)).get();}
        catch(ReflectiveOperationException|RuntimeException error){return false;}
    }
    private void closeCards(){
        for(var w:openCards)try{Object card=w.get();if(card!=null)closeCard.invoke(card,false);}
        catch(ReflectiveOperationException|RuntimeException error){log("ui_card_close_failed");}
        openCards.clear();
    }
    void close(){
        if(pairingSubscription!=null)pairingSubscription.close();
        closed=true;client.onProjection(null);
        if(finderUi!=null)finderUi.close();
        if(screenCard!=null)screenCard.close();
        if(networkCard!=null)networkCard.close();
        main.post(()->{closeCards();for(Discovery d:discoveries)withdraw(d);discoveries.clear();projection=null;});
        for(HookHandle hook:hooks)hook.unhook();hooks.clear();
    }
}
