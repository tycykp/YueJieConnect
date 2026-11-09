package io.github.qiangjian.connect;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import org.json.JSONObject;

/** Adapts the selected, authenticated peer to the OEM floating screen window. */
final class ScreenCastSinkRuntime {
    private final BridgeClient client;
    private final ClassLoader loader;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScreenCastBridge bridge;
    private final XiaomiScreenSinkAdapter sinks;
    private final Class<?> viewType;
    private ScreenCastSession.Scope scope;
    private String peerId;
    private Object peer,view,nativeSink;
    private ScreenCastFormat format;
    private boolean consent;
    ScreenCastSinkRuntime(XposedModule module,BridgeClient client,ClassLoader loader)throws Exception{
        this.client=client;this.loader=loader;
        viewType=type("com.xiaomi.mirror.sink.SinkView");
        ScreenCastBridgeTransport transport=new ScreenCastBridgeTransport(client);
        bridge=new ScreenCastBridge(transport,new ScreenCastBridge.Listener(){
            public void onStreaming(Object owner,ScreenCastSession.Scope scope,ScreenCastSession.Receipt receipt){ScreenCastLog.i("QiangJianScreen","sink_first_frame pts="+receipt.presentationTimeUs());}
            public void onClosed(Object owner,ScreenCastSession.Scope scope,String code){ScreenCastLog.w("QiangJianScreen","sink_closed "+code);}
        },SystemClock::elapsedRealtime);
        sinks=new XiaomiScreenSinkAdapter(module,loader);sinks.install();
        transport.onAllowed(this::allowed);transport.attach(bridge);
        module.hook(type("com.xiaomi.mirror.j0").getDeclaredMethod("b0",String.class,int.class)).setId("qj.screen.entry").intercept(chain->{
            JSONObject p=client.projection();
            if(p==null||!p.optString("id").equals(chain.getArg(0)))return chain.proceed();
            main.post(this::start);return 0;
        });
        module.hook(type("com.xiaomi.mirror.connection.G").getDeclaredMethod("t0",String.class)).setId("qj.screen.peer").intercept(chain->{
            if(current()&&peerId.equals(chain.getArg(0))&&peer!=null)return peer;
            return chain.proceed();
        });
        module.hook(viewType.getDeclaredMethod("H0")).setId("qj.screen.native.channel").intercept(chain->{
            if(owns(chain.getThisObject()))return null;return chain.proceed();
        });
        module.hook(viewType.getDeclaredMethod("a1",int.class)).setId("qj.screen.native.surface").intercept(chain->{
            if(!owns(chain.getThisObject()))return chain.proceed();
            Object actual=chain.getThisObject();main.post(()->bind(actual));return null;
        });
        main.post(new Runnable(){public void run(){bridge.tick();if(scope!=null&&!current()){sinks.sessionChanged();scope=null;consent=false;}main.postDelayed(this,250);}});
        ScreenCastLog.i("QiangJianScreen","sink_installed");
    }
    private Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,false,loader);}
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private boolean current(){JSONObject p=client.projection();return scope!=null&&p!=null&&scope.sessionId().equals(p.optString("session_id"))&&peerId.equals(p.optString("id"))&&client.hasLocalGrant("screen_video");}
    private boolean owns(Object actual){try{return current()&&consent&&peerId.equals(viewType.getMethod("getDeviceId").invoke(actual));}catch(Exception e){return false;}}
    private void start(){try{
        JSONObject p=client.projection();if(p==null||!client.hasLocalGrant("screen_video"))return;
        if(scope!=null){bridge.sessionChanged();sinks.sessionChanged();}
        peerId=p.getString("id");scope=new ScreenCastSession.Scope(p.getString("session_id"),(new java.security.SecureRandom().nextLong()&Long.MAX_VALUE)|1);
        consent=false;view=null;nativeSink=null;peer=null;
        client.request(peerId,scope.sessionId(),"screen_request","screen-request:"+scope.streamId(),new JSONObject().put("stream_id",scope.streamId()),r->{
            ScreenCastLog.i("QiangJianScreen","request_result "+r.optString("status")+" "+r.optString("code"));
        });
    }catch(Exception e){ScreenCastLog.e("QiangJianScreen","start_failed",e);}}
    private boolean allowed(JSONObject payload){
        if(!current()||consent||scope.streamId()!=payload.optLong("stream_id"))return false;
        try{format=new ScreenCastFormat(payload.getInt("width"),payload.getInt("height"),payload.getInt("fps"),payload.getInt("bitrate"));consent=true;main.post(this::window);return true;}
        catch(Exception e){return false;}
    }
    private void window(){try{
        if(!current()||!consent)return;
        peer=type("com.xiaomi.mirror.l0").getConstructor(String.class).newInstance(peerId);
        type("com.xiaomi.mirror.k0").getMethod("H",String.class).invoke(peer,"AndroidPhone");
        type("com.xiaomi.mirror.k0").getMethod("D",String.class).invoke(peer,client.projection().getJSONObject("real").optString("name"));
        Class<?> controller=type("com.xiaomi.mirror.sink.e"),callback=type("com.xiaomi.mirror.sink.e$d");
        Object owner=controller.getMethod("T").invoke(null);
        Object cb=Proxy.newProxyInstance(loader,new Class<?>[]{callback},(p,m,a)->ScreenCastLinkerRuntime.objectMethod(p,m.getName(),a));
        controller.getMethod("s1",boolean.class,String.class,int.class,boolean.class,callback).invoke(owner,true,peerId,0,false,cb);
        ScreenCastLog.i("QiangJianScreen","native_window_requested");
    }catch(Exception e){ScreenCastLog.e("QiangJianScreen","window_failed",e);}}
    private void bind(Object actual){try{
        if(!owns(actual)||nativeSink!=null)return;
        Surface surface=(Surface)field(viewType,"z").get(actual);if(surface==null||!surface.isValid())return;
        view=actual;var owned=scope;var sinkScope=new XiaomiScreenSinkAdapter.Scope(scope.sessionId(),scope.streamId());
        Class<?> sinkType=type("com.xiaomi.mirrorcontrol.MirrorControlSink"),callback=type("com.xiaomi.mirrorcontrol.MirrorControlSink$SinkCallback");
        var ctor=type("com.xiaomi.mirror.sink.SinkView$d").getDeclaredConstructor(viewType);ctor.setAccessible(true);
        nativeSink=sinkType.getConstructor(callback).newInstance(ctor.newInstance(actual));
        field(viewType,"p1").set(actual,nativeSink);field(viewType,"E").setBoolean(actual,true);
        var window=new XiaomiScreenRequestAdapter.NativeWindow(actual,peer,peerId,0,true,()->scope==owned&&owns(actual)&&surface.isValid());
        var expectation=bridge.sink(window,scope,format,()->consent&&scope==owned&&current(),new ScreenCastBridge.NativeEndpoint(){
            public ScreenCastFrameQueue.Offer receive(ScreenCastPacket packet){return sinks.receive(sinkScope,packet);}
            public void requestKeyFrame(){}
            public void revokeInput(){}
            public void stopNative(){main.post(()->sinks.stop(sinkScope));}
        });
        sinks.bind(nativeSink,sinkScope,format,expectation.currentAndConsented(),expectation.listener());
        sinkType.getMethod("startMirror",type("com.xiaomi.miplay.report.MirrorMetaInfo"),String.class,int.class,int.class,Surface.class)
            .invoke(nativeSink,null,"",0,0,surface);
        ScreenCastLog.i("QiangJianScreen","native_surface_bound");
    }catch(Exception e){ScreenCastLog.e("QiangJianScreen","sink_bind_failed",e);}}
}
