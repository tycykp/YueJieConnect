package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.view.WindowManager;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import org.json.JSONObject;

/** Adapts the selected, authenticated peer to the OEM floating screen window. */
final class ScreenCastSinkRuntime {
    private static final int OEM_MAGNIFICATION_OVERLAY=2027;
    private final BridgeClient client;
    private final ScreenCastInputRuntime input;
    private final ClassLoader loader;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScreenCastBridge bridge;
    private final XiaomiScreenSinkAdapter sinks;
    private final MediaControlSystemSession media;
    private final Class<?> viewType;
    private volatile ScreenCastSession.Scope scope;
    private String peerId;
    private Object peer,view,nativeSink;
    private ScreenCastFormat format;
    private ScreenCastAudio.Playback playback;
    private ScreenCastSession.Scope mediaStream;
    private boolean consent;
    private boolean closing;
    private Boolean lastCardQuery;
    ScreenCastSession.Scope dragScope(){return current()&&!closing&&view!=null&&bridge.streaming(view,scope)?scope:null;}
    android.view.View dragView(){return dragScope()==null?null:(android.view.View)view;}
    void releaseDragPointer(){input.releasePointer();}
    boolean dragPointerHeld(ScreenCastSession.Scope expected){return input.pointerHeld(expected);}
    boolean captureDrag(ScreenCastSession.Scope expected){return input.captureDrag(expected);}
    ScreenCastSinkRuntime(XposedModule module,BridgeClient client,ClassLoader loader)throws Exception{
        this.client=client;this.loader=loader;
        input=new ScreenCastInputRuntime(client);
        media=new MediaControlSystemSession(client.context(),
            new MediaControlNativeRoute(new MediaControlBridgeTransport(client),SystemClock::elapsedRealtime),
            (scope,code,attempted)->ScreenCastLog.w("YueJieMedia","control_failed "+code+" attempted="+attempted));
        new XiaomiScreenTouchAdapter(module,loader,input,actual->actual==view&&owns(actual),actual->actual==peer&&current());
        viewType=type("com.xiaomi.mirror.sink.SinkView");
        ScreenCastBridgeTransport transport=new ScreenCastBridgeTransport(client);
        bridge=new ScreenCastBridge(transport,new ScreenCastBridge.Listener(){
            public void onStreaming(Object owner,ScreenCastSession.Scope owned,ScreenCastSession.Receipt receipt){
                ScreenCastLog.i("YueJieScreen","sink_first_frame pts="+receipt.presentationTimeUs());
                main.post(()->{
                    if(playback!=null&&scope==owned&&view==owner&&!closing&&current())
                        playback.start(
                            ()->main.post(()->sendAudioControl(transport,owned,owner,ScreenCastBridge.Action.AUDIO_READY)),
                            ()->main.post(()->sendAudioControl(transport,owned,owner,ScreenCastBridge.Action.AUDIO_STOP)));
                });
            }
            public void onClosed(Object owner,ScreenCastSession.Scope scope,String code){ScreenCastLog.w("YueJieScreen","sink_closed "+code);dismiss(scope);}
        },SystemClock::elapsedRealtime);
        sinks=new XiaomiScreenSinkAdapter(module,loader);sinks.install();
        transport.onAllowed(this::allowed);transport.attach(bridge);
        module.hook(type("com.xiaomi.mirror.provider.CallProvider").getDeclaredMethod("call",String.class,String.class,android.os.Bundle.class))
            .setId("yj.screen.card.query").intercept(chain->{
                if(!XiaomiScreenCardState.METHOD.equals(chain.getArg(0)))return chain.proceed();
                android.os.Bundle result=new android.os.Bundle();
                int caller=android.os.Binder.getCallingUid();long identity=android.os.Binder.clearCallingIdentity();
                try{
                    var verified=CallerVerifier.resolve(client.context().getPackageManager(),caller);
                    var request=(android.os.Bundle)chain.getArg(2);var owned=scope;
                    boolean valid=verified!=null&&CallerPolicy.XIAOMI.equals(verified.packageName())&&request!=null
                        &&owned!=null&&owned.sessionId().equals(request.getString("session_id"))&&current()
                        &&peerId.equals(chain.getArg(1))&&!closing&&view!=null;
                    boolean active=valid&&bridge.streaming(view,owned);
                    if(!Boolean.valueOf(active).equals(lastCardQuery)){lastCardQuery=active;ScreenCastLog.i("YueJieScreen","card_query active="+active+" verified="+(verified!=null)+" current="+current());}
                    result.putBoolean("active",active);return result;
                }finally{android.os.Binder.restoreCallingIdentity(identity);}
            });
        Class<?> helper=type("com.xiaomi.mirror.j0");
        hooksClose(module,helper.getDeclaredMethod("j"),false);
        hooksClose(module,helper.getDeclaredMethod("k",String.class),true);
        Class<?> windowType=type("com.xiaomi.mirror.sink.SinkWindow");
        installWindowLayer(module,windowType);
        module.hook(windowType.getMethod("f1")).setId("yj.screen.window.close").intercept(chain->{
            var owned=scope;boolean selected=owned!=null&&view!=null&&field(windowType,"h").get(chain.getThisObject())==view;
            Object result=chain.proceed();if(selected)stopOnly(owned);return result;
        });
        module.hook(type("com.xiaomi.mirror.j0").getDeclaredMethod("b0",String.class,int.class)).setId("yj.screen.entry").intercept(chain->{
            JSONObject p=client.projection();
            if(p==null||!p.optString("id").equals(chain.getArg(0)))return chain.proceed();
            main.post(this::start);return 0;
        });
        module.hook(type("com.xiaomi.mirror.connection.G").getDeclaredMethod("t0",String.class)).setId("yj.screen.peer").intercept(chain->{
            if(current()&&peerId.equals(chain.getArg(0))&&peer!=null)return peer;
            return chain.proceed();
        });
        module.hook(viewType.getDeclaredMethod("H0")).setId("yj.screen.native.channel").intercept(chain->{
            if(owns(chain.getThisObject()))return null;return chain.proceed();
        });
        module.hook(viewType.getDeclaredMethod("a1",int.class)).setId("yj.screen.native.surface").intercept(chain->{
            if(!owns(chain.getThisObject()))return chain.proceed();
            Object actual=chain.getThisObject();main.post(()->bind(actual));return null;
        });
        main.post(new Runnable(){public void run(){
            bridge.tick();
            if(scope!=null&&!current()){dismiss(scope);sinks.sessionChanged();}
            refreshMedia();
            main.postDelayed(this,250);
        }});
        ScreenCastLog.i("YueJieScreen","sink_installed");
    }
    private void installWindowLayer(XposedModule module,Class<?> windowType)throws Exception{
        Field sink=field(windowType,"h"), layout=field(windowType,"y1");
        ThreadLocal<Object> opening=new ThreadLocal<>();
        module.hook(windowType.getDeclaredMethod("y1",int.class,int.class)).setId("yj.screen.window.layout").intercept(chain->{
            Object owner=chain.getThisObject();
            if(!owns(sink.get(owner)))return chain.proceed();
            Object previous=opening.get();
            opening.set(owner);
            try{return chain.proceed();}
            finally{if(previous==null)opening.remove();else opening.set(previous);}
        });
        // The OEM prepares these same params immediately before adding the window. Set the
        // type before attachment so native moves and resizing keep the same window type.
        module.hook(type("q4.u0").getDeclaredMethod("H0",WindowManager.LayoutParams.class)).setId("yj.screen.window.layer").intercept(chain->{
            Object result=chain.proceed(), owner=opening.get();
            var params=(WindowManager.LayoutParams)chain.getArg(0);
            if(owner!=null&&layout.get(owner)==params&&params.type==OEM_MAGNIFICATION_OVERLAY)
                params.type=WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            return result;
        });
    }
    private void refreshMedia(){
        media.tick();
        var owned=dragScope();
        if(owned==null||mediaStream==owned||!client.hasLocalGrant(MediaControlTasks.OPERATION))return;
        JSONObject projection=client.projection();
        var operations=projection==null?null:projection.optJSONArray("operations");
        boolean ready=false;
        if(operations!=null)for(int i=0;i<operations.length();i++)
            if(MediaControlTasks.OPERATION.equals(operations.optString(i)))ready=true;
        if(!ready)return;
        Object nativePeer=peer,nativeView=view;
        var controlScope=new MediaControlNativeRoute.Scope(peerId,owned.sessionId(),peerId,owned.streamId(),0);
        mediaStream=owned;
        try{
            media.start(controlScope,nativePeer,MediaControlEndpoint.ACTIVE_SESSION,
                ()->scope==owned&&peer==nativePeer&&view==nativeView&&dragScope()==owned
                    &&client.hasLocalGrant(MediaControlTasks.OPERATION));
        }catch(RuntimeException unavailable){
            ScreenCastLog.w("YueJieMedia","sink_control_unavailable "+unavailable.getMessage());
        }
    }
    private void sendAudioControl(ScreenCastBridgeTransport transport,ScreenCastSession.Scope owned,Object owner,ScreenCastBridge.Action action){
        if(scope!=owned||view!=owner||closing||!current())return;
        if(!transport.send(new ScreenCastBridge.Control(owned,action,null,0,0))&&action==ScreenCastBridge.Action.AUDIO_STOP)
            bridge.stop(owned,"screen_audio_stop_failed");
    }
    private void hooksClose(XposedModule module,java.lang.reflect.Method method,boolean targeted){
        module.hook(method).setId("yj.screen.close."+method.getName()).intercept(chain->{
            var owned=scope;if(owned==null||!current()||targeted&&!peerId.equals(chain.getArg(0)))return chain.proceed();
            stopOnly(owned);return null;
        });
    }
    private void stopOnly(ScreenCastSession.Scope owned){main.post(()->{
        if(scope!=owned)return;
        ScreenCastLog.i("YueJieScreen","native_stop_only");
        bridge.stop(owned,"screen_user_stopped");dismiss(owned);
    });}
    private Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,false,loader);}
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private boolean current(){JSONObject p=client.projection();return scope!=null&&p!=null&&scope.sessionId().equals(p.optString("session_id"))&&peerId.equals(p.optString("id"))&&client.hasLocalGrant("screen_video");}
    private boolean owns(Object actual){try{return current()&&consent&&peerId.equals(viewType.getMethod("getDeviceId").invoke(actual));}catch(Exception e){return false;}}
    private void start(){try{
        JSONObject p=client.projection();if(p==null||!client.hasLocalGrant("screen_video"))return;
        if(current()&&nativeSink!=null&&!closing&&bridge.streaming(view,scope)){
            Class<?> controller=type("com.xiaomi.mirror.sink.e");Object owner=controller.getMethod("T").invoke(null);
            Object window=field(controller,"h").get(owner);
            if(window!=null)window.getClass().getMethod("e2").invoke(window);
            return;
        }
        if(scope!=null&&closing){main.postDelayed(this::start,250);return;}
        if(scope!=null){bridge.sessionChanged();sinks.sessionChanged();}
        peerId=p.getString("id");scope=new ScreenCastSession.Scope(p.getString("session_id"),(new java.security.SecureRandom().nextLong()&Long.MAX_VALUE)|1);
        consent=false;closing=false;view=null;nativeSink=null;peer=null;playback=null;
        client.request(peerId,scope.sessionId(),"screen_request","screen-request:"+scope.streamId(),new JSONObject().put("stream_id",scope.streamId()),r->{
            ScreenCastLog.i("YueJieScreen","request_result "+r.optString("status")+" "+r.optString("code"));
        });
    }catch(Exception e){ScreenCastLog.e("YueJieScreen","start_failed",e);}}
    private void dismiss(ScreenCastSession.Scope owned){main.post(()->{
        if(scope!=owned||closing)return;closing=true;input.revoke();media.stop();mediaStream=null;
        try{Class<?> controller=type("com.xiaomi.mirror.sink.e");Object owner=controller.getMethod("T").invoke(null);
            if(view!=null&&field(controller,"g").get(owner)==view)controller.getMethod("O").invoke(owner);
        }catch(Exception error){ScreenCastLog.e("YueJieScreen","window_close_failed",error);}
        finally{main.postDelayed(()->{if(scope==owned){scope=null;consent=false;nativeSink=null;view=null;peer=null;playback=null;}closing=false;},250);}
    });}
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
        ScreenCastLog.i("YueJieScreen","native_window_requested");
    }catch(Exception e){ScreenCastLog.e("YueJieScreen","window_failed",e);}}
    private void bind(Object actual){try{
        if(!owns(actual)||nativeSink!=null)return;
        Surface surface=(Surface)field(viewType,"z").get(actual);if(surface==null||!surface.isValid())return;
        Object geometry=field(viewType,"A").get(actual);
        field(geometry.getClass(),"b").setInt(geometry,format.width());field(geometry.getClass(),"c").setInt(geometry,format.height());
        field(geometry.getClass(),"d").setFloat(geometry,(float)format.height()/format.width());
        view=actual;var owned=scope;var sinkScope=new XiaomiScreenSinkAdapter.Scope(scope.sessionId(),scope.streamId());
        // The bridge replaces the native decoder's onMirrorStart callback; attach its
        // original drag handler explicitly alongside the already bound touch adapter.
        android.view.View dragArea=(android.view.View)field(viewType,"B4").get(actual);
        if(dragArea==null)dragArea=(android.view.View)actual;
        dragArea.setOnDragListener((android.view.View.OnDragListener)field(viewType,"D4").get(actual));
        ScreenCastLog.i("YueJieFiles","native_window_drag_attached");
        Class<?> sinkType=type("com.xiaomi.mirrorcontrol.MirrorControlSink"),callback=type("com.xiaomi.mirrorcontrol.MirrorControlSink$SinkCallback");
        var ctor=type("com.xiaomi.mirror.sink.SinkView$d").getDeclaredConstructor(viewType);ctor.setAccessible(true);
        nativeSink=sinkType.getConstructor(callback).newInstance(ctor.newInstance(actual));
        field(viewType,"p1").set(actual,nativeSink);field(viewType,"E").setBoolean(actual,true);
        var window=new XiaomiScreenRequestAdapter.NativeWindow(actual,peer,peerId,0,true,()->scope==owned&&owns(actual)&&surface.isValid());
        var audio=new ScreenCastAudio.Playback();
        playback=audio;
        var expectation=bridge.sink(window,scope,format,()->consent&&scope==owned&&current(),new ScreenCastBridge.NativeEndpoint(){
            public ScreenCastFrameQueue.Offer receive(ScreenCastPacket packet){return packet.isAudio()?audio.offer(packet):sinks.receive(sinkScope,packet);}
            public void requestKeyFrame(){}
            public void revokeInput(){input.revoke();}
            public void stopNative(){audio.close();main.post(()->sinks.stop(sinkScope));}
        });
        input.bind(owned,format,()->scope==owned&&!closing&&current()&&bridge.streaming(actual,owned),false);
        sinks.bind(nativeSink,sinkScope,format,expectation.currentAndConsented(),expectation.listener());
        sinkType.getMethod("startMirror",type("com.xiaomi.miplay.report.MirrorMetaInfo"),String.class,int.class,int.class,Surface.class)
            .invoke(nativeSink,null,"",0,0,surface);
        ScreenCastLog.i("YueJieScreen","native_surface_bound");
    }catch(Exception e){ScreenCastLog.e("YueJieScreen","sink_bind_failed",e);}}
}
