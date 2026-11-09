package io.github.qiangjian.connect;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Exact control messages and session-owned PFD transport for the native screen assembly. */
final class ScreenCastBridgeTransport implements ScreenCastBridge.Transport,BridgeClient.VendorExecutor,AutoCloseable {
    private final BridgeClient client;
    private final AtomicLong serial=new AtomicLong();
    private final String prefix="screen:"+java.util.UUID.randomUUID()+":";
    private ScreenCastBridge bridge;
    private BridgeClient.Subscription registration;
    private java.util.function.Predicate<JSONObject> allowedHandler;
    void onAllowed(java.util.function.Predicate<JSONObject> handler){allowedHandler=handler;}
    ScreenCastBridgeTransport(BridgeClient client){this.client=client;}
    void attach(ScreenCastBridge actual){
        if(bridge!=null)throw new IllegalStateException("screen_already_bound");bridge=java.util.Objects.requireNonNull(actual);
        registration=client.registerExecutor("screen_control",this);
    }
    @Override public CompletionStage<ScreenCastBridge.Pipe> open(ScreenCastSession.Scope scope,ScreenCastSession.Role role){
        JSONObject p=client.projection();
        if(p==null||!scope.sessionId().equals(p.optString("session_id")))return java.util.concurrent.CompletableFuture.failedStage(new IllegalStateException("stale_session"));
        return client.openNativeMedia(p.optString("id"),scope.sessionId(),scope.streamId(),"screen_video",role==ScreenCastSession.Role.SOURCE?"sender":"receiver").thenApply(pipe->new ScreenCastBridge.Pipe(){
            public boolean offer(byte[] bytes){return pipe.offer(bytes);}
            public void setReceiver(java.util.function.Consumer<byte[]> callback){pipe.setReceiver(callback);}
            public void setOnClosed(Runnable callback){pipe.setOnClosed(callback);}
            public void close(){pipe.close();}
        });
    }
    @Override public boolean send(ScreenCastBridge.Control message){
        JSONObject p=client.projection();if(bridge==null||p==null||!message.scope().sessionId().equals(p.optString("session_id")))return false;
        try{
            JSONObject payload=encode(message);
            client.request(p.optString("id"),message.scope().sessionId(),"screen_control",prefix+serial.incrementAndGet(),payload,response->{
                JSONObject result=response.optJSONObject("result"),data=result==null?null:result.optJSONObject("data");
                if(!"ok".equals(response.optString("status"))||data==null||!"screen_control".equals(result.optString("operation"))
                    ||data.optLong("stream_id")!=message.scope().streamId()||!payload.optString("action").equals(data.optString("action"))||!Boolean.TRUE.equals(data.opt("accepted")))
                    {ScreenCastLog.w("QiangJianScreen","control_rejected "+message.action()+" "+response.optString("code"));cancel(message.scope());}
            });return true;
        }catch(org.json.JSONException invalid){return false;}
    }
    private static JSONObject encode(ScreenCastBridge.Control value)throws org.json.JSONException{
        JSONObject p=new JSONObject().put("stream_id",value.scope().streamId()).put("action",value.action().name().toLowerCase(java.util.Locale.ROOT));
        if(value.format()!=null)p.put("width",value.format().width()).put("height",value.format().height()).put("fps",value.format().fps()).put("bitrate",value.format().bitrate());
        if(value.action()==ScreenCastBridge.Action.RENDERED)p.put("sequence",value.sequence()).put("presentation_time_us",value.presentationTimeUs());return p;
    }
    @Override public void execute(JSONObject task,BridgeClient.TaskCompletion completion){
        try{
            JSONObject p=client.projection(),payload=task.getJSONObject("payload");String session=task.getString("session_id");
            if(bridge==null||p==null||!session.equals(p.optString("session_id"))||!task.getString("projection_id").equals(p.optString("id"))||!client.hasLocalGrant("screen_video"))throw new IllegalStateException("stale_session");
            if("allowed".equals(payload.optString("action"))){
                if(allowedHandler==null||!allowedHandler.test(payload)){completion.fail("native_unavailable");return;}
                completion.success(new JSONObject().put("stream_id",payload.getLong("stream_id")).put("action","allowed").put("accepted",true));return;
            }
            ScreenCastBridge.Action action=ScreenCastBridge.Action.valueOf(payload.getString("action").toUpperCase(java.util.Locale.ROOT));
            ScreenCastFormat format=action==ScreenCastBridge.Action.PREPARED?new ScreenCastFormat(payload.getInt("width"),payload.getInt("height"),payload.getInt("fps"),payload.getInt("bitrate")):null;
            var control=new ScreenCastBridge.Control(new ScreenCastSession.Scope(session,payload.getLong("stream_id")),action,format,
                action==ScreenCastBridge.Action.RENDERED?payload.getLong("sequence"):0,action==ScreenCastBridge.Action.RENDERED?payload.getLong("presentation_time_us"):0);
            if(!bridge.control(control)){completion.fail("native_unavailable");return;}
            completion.success(new JSONObject().put("stream_id",control.scope().streamId()).put("action",payload.getString("action")).put("accepted",true));
        }catch(Exception invalid){completion.fail("invalid_payload");}
    }
    @Override public void cancel(ScreenCastSession.Scope scope){client.closeNativeMedia(scope.sessionId(),scope.streamId());}
    @Override public void cancel(String session,String request){} // Control delivery is synchronous; no deferred native side effect.
    @Override public void sessionChanged(){if(bridge!=null)bridge.sessionChanged();}
    @Override public void close(){if(registration!=null){registration.close();registration=null;}if(bridge!=null)bridge.close();bridge=null;}
}
