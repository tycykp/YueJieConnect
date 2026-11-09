package io.github.qiangjian.connect;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.github.libxposed.api.XposedModule;
import org.json.JSONObject;

/** OEM consent and MediaProjection, with the authenticated bridge replacing proprietary ODMF. */
final class ScreenCastSourceRuntime {
    private final BridgeClient client;
    private final ClassLoader loader;
    private final OplusScreenRequestAdapter requests;
    private final ScreenCastBridge bridge;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile ScreenCastSession.Scope scope;
    private volatile boolean approved;
    private ScreenCastProjectionSource capture;
    private final Class<?> projectionType;
    private final Object projectionOwner;
    private android.media.projection.MediaProjection ownedProjection;
    private long nextRenew,deadline;
    ScreenCastSourceRuntime(XposedModule module,BridgeClient client,ClassLoader loader)throws Exception{
        this.client=client;this.loader=loader;
        projectionType=Class.forName("u2.c",false,loader);
        projectionOwner=projectionType.getMethod("d",android.content.Context.class).invoke(null,client.context());
        ScreenCastBridgeTransport transport=new ScreenCastBridgeTransport(client);
        bridge=new ScreenCastBridge(transport,new ScreenCastBridge.Listener(){
            public void onStreaming(Object owner,ScreenCastSession.Scope scope,ScreenCastSession.Receipt receipt){ScreenCastLog.i("QiangJianScreen","source_render_confirmed");}
            public void onClosed(Object owner,ScreenCastSession.Scope scope,String code){ScreenCastLog.w("QiangJianScreen","source_closed "+code);}
        },SystemClock::elapsedRealtime);
        requests=new OplusScreenRequestAdapter(module,loader,this::resolve);requests.install();transport.attach(bridge);
        main.post(new Runnable(){public void run(){
            requests.tick();bridge.tick();var owned=scope;
            if(owned!=null){
                if(!current(owned)||!approved&&SystemClock.elapsedRealtime()>=deadline)stop(owned);
                else if(approved&&SystemClock.elapsedRealtime()>=nextRenew){nextRenew=SystemClock.elapsedRealtime()+1000;if(!command("screen_request_renew",owned))stop(owned);}
            }main.postDelayed(this,250);
        }});
        ScreenCastLog.i("QiangJianScreen","source_installed");
    }
    private boolean command(String op,ScreenCastSession.Scope owned){try{return "ok".equals(client.nativeScreenRequest(new JSONObject().put("op",op).put("session_id",owned.sessionId()).put("stream_id",owned.streamId())).optString("status"));}catch(Exception e){return false;}}
    private boolean current(ScreenCastSession.Scope owned){JSONObject p=client.projection();return scope==owned&&p!=null&&owned.sessionId().equals(p.optString("session_id"))&&client.hasLocalGrant("screen_video");}
    private void stop(ScreenCastSession.Scope owned){main.post(()->{
        if(scope!=owned)return;scope=null;approved=false;
        if(capture!=null){capture.close();capture=null;}
        try{if(ownedProjection!=null&&projectionType.getMethod("e").invoke(projectionOwner)==ownedProjection)projectionType.getMethod("g").invoke(projectionOwner);}catch(Exception error){ScreenCastLog.e("QiangJianScreen","projection_release_failed",error);}finally{ownedProjection=null;}
        requests.cancel(new OplusScreenSourceAdapter.Scope(owned.sessionId(),owned.streamId()));
        command("screen_request_revoke",owned);bridge.sessionChanged();
    });}
    private OplusScreenRequestAdapter.Expectation resolve(OplusScreenRequestAdapter.NativeRequest request){
        if(request.requestProof()==null)return null;
        try{
            if(scope!=null)throw new IllegalStateException("screen_busy");
            if(projectionType.getMethod("e").invoke(projectionOwner)!=null)throw new IllegalStateException("native_capture_busy");
            JSONObject claim=client.nativeScreenRequest(new JSONObject().put("op","screen_request_claim").put("proof",request.requestProof())
                .put("original_caller_uid",request.callerUid()).put("original_caller_pid",request.callerPid())
                .put("native_uid",request.nativeUid()).put("device_type",request.deviceType()));
            if(!"ok".equals(claim.optString("status")))throw new IllegalStateException(claim.optString("code"));
            var owned=new ScreenCastSession.Scope(claim.getString("session_id"),claim.getLong("stream_id"));scope=owned;approved=false;deadline=SystemClock.elapsedRealtime()+120000;
            var metrics=client.context().getResources().getDisplayMetrics();
            var display=client.context().getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0);
            var size=new android.graphics.Point();display.getRealSize(size);
            ScreenCastFormat format=new ScreenCastFormat(size.x&~1,size.y&~1,30,8_000_000);
            var listener=new OplusScreenRequestAdapter.ConsentListener(){
                public boolean usesBridgeCapture(){return true;}
                public void onNativeAllowed(Object actual){if(actual==request.deviceInfo()){
                    try{ownedProjection=(android.media.projection.MediaProjection)projectionType.getMethod("e").invoke(projectionOwner);}
                    catch(Exception error){stop(owned);return;}
                    main.post(()->begin(actual,owned,format,metrics.densityDpi));
                }}
                public boolean offer(ScreenCastPacket packet){return false;}
                public void onFailure(String code){ScreenCastLog.w("QiangJianScreen",code);stop(owned);}
                public void onPrepared(int id){}
                public void onStopped(){stop(owned);}
            };
            ScreenCastLog.i("QiangJianScreen","native_request_claimed");
            return new OplusScreenRequestAdapter.Expectation(new OplusScreenSourceAdapter.Scope(owned.sessionId(),owned.streamId()),format,deadline,()->current(owned),listener);
        }catch(Exception e){ScreenCastLog.e("QiangJianScreen","claim_failed",e);throw new IllegalStateException(e);}
    }
    private void begin(Object actual,ScreenCastSession.Scope owned,ScreenCastFormat format,int density){try{
        if(!current(owned)||approved)return;
        if(!command("screen_request_allowed",owned))throw new IllegalStateException("native_consent_unproved");approved=true;nextRenew=0;
        JSONObject p=client.projection();
        JSONObject payload=new JSONObject().put("action","allowed").put("stream_id",owned.streamId()).put("width",format.width()).put("height",format.height()).put("fps",format.fps()).put("bitrate",format.bitrate());
        client.request(p.getString("id"),owned.sessionId(),"screen_control","screen-allowed:"+owned.streamId(),payload,r->{if(!"ok".equals(r.optString("status")))stop(owned);});
        var endpoint=new ScreenCastBridge.NativeEndpoint(){
            public ScreenCastFrameQueue.Offer receive(ScreenCastPacket packet){return ScreenCastFrameQueue.Offer.CLOSED;}
            public void requestKeyFrame(){main.post(()->{if(current(owned)&&capture!=null)capture.requestKeyFrame();});}
            public void revokeInput(){}
            public void stopNative(){stop(owned);}
        };
        bridge.sourceFactory(actual,owned,format,endpoint,code->ScreenCastLog.w("QiangJianScreen",code))
            .openAfterNativeConsent(actual,()->approved&&current(owned)).whenComplete((session,error)->main.post(()->{
                try{
                    if(error!=null||!current(owned)){if(session!=null)session.close();return;}
                    var projection=ownedProjection;
                    if(projection==null||projectionType.getMethod("e").invoke(projectionOwner)!=projection)throw new IllegalStateException("native_capture_changed");
                    var encoder=session.sourceListener(actual);
                    capture=new ScreenCastProjectionSource(projection,owned.streamId(),format,density,encoder);
                    encoder.onPrepared(capture.displayId());ScreenCastLog.i("QiangJianScreen","capture_prepared "+format.width()+"x"+format.height());
                }catch(Exception e){ScreenCastLog.e("QiangJianScreen","capture_failed",e);stop(owned);}
            }));
    }catch(Exception e){ScreenCastLog.e("QiangJianScreen","begin_failed",e);stop(owned);}}
}
