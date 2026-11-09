package io.github.yuejie.connect;

import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Predicate;
import org.json.JSONObject;

/** Consume the real OEM finger messages before their original pooled send. */
final class XiaomiScreenTouchAdapter {
    XiaomiScreenTouchAdapter(XposedModule module,ClassLoader loader,ScreenCastInputRuntime input,
            Predicate<Object> selectedView,Predicate<Object> selectedPeer)throws Exception {
        Class<?> view=Class.forName("com.xiaomi.mirror.sink.SinkView",false,loader);
        ThreadLocal<Boolean> nativeMouse=new ThreadLocal<>();
        // The OEM touch area can live outside SinkView's ViewGroup. Capture at its
        // actual callback, before r() converts mouse input into a FingerMessage.
        module.hook(view.getDeclaredMethod("r",view,android.view.View.class,android.view.MotionEvent.class)).setId("yj.screen.pointer.source").intercept(chain->{
            if(!selectedView.test(chain.getArg(0)))return chain.proceed();
            Boolean previous=nativeMouse.get();android.view.MotionEvent event=(android.view.MotionEvent)chain.getArg(2);
            nativeMouse.set(event.isFromSource(android.view.InputDevice.SOURCE_MOUSE));
            try{return chain.proceed();}finally{if(previous==null)nativeMouse.remove();else nativeMouse.set(previous);}
        });
        Class<?> message=Class.forName("com.xiaomi.mirror.message.Message",false,loader);
        Class<?> finger=Class.forName("com.xiaomi.mirror.message.FingerMessage",false,loader);
        Class<?> mouse=Class.forName("com.xiaomi.mirror.message.MouseMessage",false,loader);
        Class<?> key=Class.forName("com.xiaomi.mirror.message.KeyMessage",false,loader);
        Class<?> recyclable=Class.forName("com.xiaomi.mirror.message.Recyclable",false,loader);
        var recycle=Class.forName("com.xiaomi.mirror.message.MessageFactory",false,loader).getMethod("recycle",recyclable);
        module.hook(view.getDeclaredMethod("l1",message)).setId("yj.screen.finger").intercept(chain->{
            Object owner=chain.getThisObject(),event=chain.getArg(0);
            if(!selectedView.test(owner)||event==null||(event.getClass()!=finger&&event.getClass()!=mouse))return chain.proceed();
            try {
                Object geometry=field(view,"A").get(owner);
                int width=field(geometry.getClass(),"b").getInt(geometry),height=field(geometry.getClass(),"c").getInt(geometry);
                int action=field(event.getClass(),"action").getInt(event),x,y;
                if(event.getClass()==finger){
                    List<?> coords=(List<?>)field(finger,"coords").get(event);
                    if(coords==null||coords.size()!=1||action>3){input.send(new JSONObject().put("kind","pointer").put("action",3).put("x",0).put("y",0));return null;}
                    Object coord=coords.get(0);x=field(coord.getClass(),"x").getInt(coord);y=field(coord.getClass(),"y").getInt(coord);
                }else{
                    action=switch(action){case 1->0;case 2->1;case 3->2;default->-1;};
                    if(action<0)return null;
                    x=field(mouse,"x").getInt(event);y=field(mouse,"y").getInt(event);
                }
                if(width>1&&height>1)input.send(new JSONObject().put("kind","pointer").put("action",action)
                    .put("mouse",event.getClass()==mouse||Boolean.TRUE.equals(nativeMouse.get()))
                    .put("x",Math.max(0,Math.min(1,(double)x/(width-1)))).put("y",Math.max(0,Math.min(1,(double)y/(height-1)))));
            }catch(Exception error){ScreenCastLog.e("YueJieScreen","native_touch_failed",error);input.revoke();}
            finally{recycle.invoke(null,event);}
            return null;
        });
        Class<?> peer=Class.forName("com.xiaomi.mirror.k0",false,loader);
        Class<?> callback=Class.forName("com.xiaomi.mirror.message.MessageManager$SendCallback",false,loader);
        module.hook(Class.forName("com.xiaomi.mirror.message.MessageManagerImpl",false,loader).getMethod("send",message,peer,callback))
            .setId("yj.screen.key").intercept(chain->{
                Object event=chain.getArg(0);
                if(event==null||event.getClass()!=key||!selectedPeer.test(chain.getArg(1)))return chain.proceed();
                try{
                    String text=(String)field(key,"text").get(event);
                    if(text==null||text.isEmpty())input.send(new JSONObject().put("kind","key")
                        .put("code",field(key,"keyCode").getInt(event)).put("down",field(key,"down").getBoolean(event)));
                }finally{recycle.invoke(null,event);}
                return null;
            });
    }
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
}
