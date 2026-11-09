package io.github.qiangjian.connect;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Feed the existing PhoneCard renderer/click listener with the actual native render state. */
final class XiaomiScreenCardState implements AutoCloseable {
    static final String METHOD="io.github.qiangjian.connect.screen_state";
    private final BridgeClient client;
    private final BooleanSupplier allowed;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final WeakHashMap<Object,Boolean> cards=new WeakHashMap<>();
    private final java.util.List<io.github.libxposed.api.XposedInterface.HookHandle> hooks=new java.util.ArrayList<>();
    private final Method info,showing,status,readStatus,render;
    private final java.lang.reflect.Field id;
    private boolean closed;
    XiaomiScreenCardState(XposedModule module,BridgeClient client,ClassLoader loader,BooleanSupplier allowed)throws ReflectiveOperationException{
        this.client=client;this.allowed=allowed;
        Class<?> phone=Class.forName("com.miui.circulate.world.sticker.blecard.PhoneCard",false,loader);
        Class<?> device=Class.forName("com.miui.circulate.api.service.CirculateDeviceInfo",false,loader);
        info=phone.getMethod("getMInfo");id=device.getField("id");
        showing=phone.getMethod("setShowingMirror",boolean.class);status=phone.getMethod("setMirrorStatus",int.class);readStatus=phone.getMethod("getMirrorStatus");render=phone.getMethod("M2");
        hooks.add(module.hook(render).setId("qj.screen.card.render").intercept(chain->{
            Object card=chain.getThisObject();
            if(phone.isInstance(card)){Object selected=info.invoke(card);Boolean active=selected==null?null:state((String)id.get(selected));
                if(active!=null){showing.invoke(card,active);status.invoke(card,active?1:0);}}
            return chain.proceed();
        }));
        hooks.add(module.hook(phone.getDeclaredMethod("H2")).setId("qj.screen.card.state").intercept(chain->{
            Object result=chain.proceed();Object card=chain.getThisObject();refresh(card,true);return result;
        }));
        Class<?> desktop=Class.forName("com.miui.circulate.api.protocol.synergy.manager.DesktopSynergyManager",false,loader);
        hooks.add(module.hook(desktop.getMethod("isSynergy",String.class)).setId("qj.screen.card.active").intercept(chain->{
            Boolean active=state((String)chain.getArg(0));return active==null?chain.proceed():active;
        }));
        main.post(new Runnable(){public void run(){if(closed)return;
            for(Object card:new java.util.ArrayList<>(cards.keySet())){
                if(card instanceof android.view.View v&&v.isAttachedToWindow())refresh(card,false);else cards.remove(card);
            }main.postDelayed(this,750);
        }});
    }
    private Boolean state(String peer){
        JSONObject p=client.projection();
        if(closed||!allowed.getAsBoolean()||p==null||!p.optString("id").equals(peer))return null;
        try{
            Bundle request=new Bundle();request.putString("session_id",p.getString("session_id"));
            Bundle result=client.context().getContentResolver().call(android.net.Uri.parse("content://com.xiaomi.mirror.callprovider"),METHOD,peer,request);
            return result!=null&&result.getBoolean("active",false);
        }catch(Exception failure){ScreenCastLog.e("QiangJianScreen","card_query_failed",failure);return false;}
    }
    private void refresh(Object card,boolean force){try{
        Object device=info.invoke(card);if(device==null)return;
        Boolean active=state((String)id.get(device));if(active==null){cards.remove(card);return;}
        Boolean old=cards.put(card,active);
        if(!active.equals(old))ScreenCastLog.i("QiangJianScreen","card_active="+active);
        if(force||!active.equals(old)||!Integer.valueOf(active?1:0).equals(readStatus.invoke(card))){showing.invoke(card,active);status.invoke(card,active?1:0);render.invoke(card);}
    }catch(ReflectiveOperationException|RuntimeException failure){ScreenCastLog.w("QiangJianScreen","card_state_unavailable");}}
    public void close(){closed=true;cards.clear();for(var hook:hooks)hook.unhook();hooks.clear();}
}
