package io.github.yuejie.connect;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.function.BooleanSupplier;

/** Ordered, independently granted input for the exact currently rendered OEM screen. */
final class ScreenCastInputRuntime implements BridgeClient.VendorExecutor {
    private final BridgeClient client;
    private final Handler worker;
    private volatile ScreenCastSession.Scope scope;
    private volatile BooleanSupplier current=()->false;
    private RemoteInputPointerTarget target;
    private long received, sent;
    private boolean source;
    private final Handler main=new Handler(android.os.Looper.getMainLooper());
    private JSONObject pendingMove;
    private double pointerX, pointerY;
    private final ScreenCastGesture gesture = new ScreenCastGesture();
    private final Runnable flushMove=()->{JSONObject move=pendingMove;pendingMove=null;if(move!=null)dispatch(move);};
    ScreenCastInputRuntime(BridgeClient client) {
        this.client=client;
        HandlerThread thread=new HandlerThread("yj-screen-input");thread.start();worker=new Handler(thread.getLooper());
        client.registerExecutor("screen_input",this);
    }
    void bind(ScreenCastSession.Scope owned, ScreenCastFormat format, BooleanSupplier rendered, boolean source) {
        revoke();scope=owned;current=rendered;sent=0;this.source=source;
        if(source)worker.post(()->{
            if(scope!=owned)return;
            try { target=new RemoteInputPointerTarget(client.context(),0,format.width(),format.height(),()->live(owned));received=0; }
            catch(Exception error){ScreenCastLog.e("YueJieScreen","input_bind_failed",error);revoke();}
        });
    }
    private boolean live(ScreenCastSession.Scope owned) {
        JSONObject p=client.projection();
        return scope==owned&&p!=null&&owned.sessionId().equals(p.optString("session_id"))
            &&client.hasLocalGrant("screen_input")&&current.getAsBoolean();
    }
    void send(JSONObject event) {
        var owned = scope;
        if (android.os.Looper.myLooper() != main.getLooper()) {
            main.post(() -> { if (scope == owned) send(event); });
            return;
        }
        if (owned == null || !live(owned)) return;
        if ("pointer".equals(event.optString("kind")) && !gesture.accept(event.optInt("action", -1))) return;
        if("pointer".equals(event.optString("kind"))&&event.optInt("action")==2){
            if(pendingMove==null)main.postDelayed(flushMove,16);
            pendingMove=event;return;
        }
        main.removeCallbacks(flushMove);flushMove.run();dispatch(event);
    }
    private void dispatch(JSONObject event) {
        var owned=scope;if(owned==null||!live(owned))return;
        try {
            if("pointer".equals(event.optString("kind"))){pointerX=event.optDouble("x");pointerY=event.optDouble("y");}
            long sequence=++sent;event.put("stream_id",owned.streamId()).put("sequence",sequence);
            client.request(client.projection().getString("id"),owned.sessionId(),"screen_input","input:"+owned.streamId()+":"+sequence,event,response->{
                JSONObject result=response.optJSONObject("result"),data=result==null?null:result.optJSONObject("data");
                if(!"ok".equals(response.optString("status"))||data==null||!data.optBoolean("accepted")
                    ||data.optLong("stream_id")!=owned.streamId()||data.optLong("sequence")!=sequence){
                    ScreenCastLog.w("YueJieScreen","input_rejected "+response.optString("code"));
                    if(scope==owned)revoke();
                }
            });
        }catch(Exception error){ScreenCastLog.e("YueJieScreen","input_send_failed",error);revoke();}
    }
    void releasePointer(){
        main.post(()->{try{send(new JSONObject().put("kind","pointer").put("action",3).put("x",pointerX).put("y",pointerY));}
            catch(org.json.JSONException ignored){}});
    }
    boolean pointerHeld(ScreenCastSession.Scope expected){
        return main.getLooper()==android.os.Looper.myLooper()&&expected!=null&&expected.equals(scope)&&live(scope)&&gesture.held();
    }
    @Override public void execute(JSONObject task,BridgeClient.TaskCompletion completion) {
        var owned=scope;
        if(owned==null){completion.fail("native_unavailable");return;}
        worker.post(()->{
            try {
                JSONObject event=task.getJSONObject("payload"),p=client.projection();
                if(!live(owned)||target==null||!owned.sessionId().equals(task.getString("session_id"))
                    ||p==null||!p.getString("id").equals(task.getString("projection_id"))||event.getLong("stream_id")!=owned.streamId()){
                    completion.fail("stale_session");return;
                }
                long sequence=event.getLong("sequence");
                if("pointer".equals(event.optString("kind"))&&event.optInt("action")==0)ScreenCastLog.i("YueJieFiles","native_pointer_down mouse="+event.optBoolean("mouse"));
                if(sequence<=received){completion.fail("replay");return;}received=sequence;
                if(SystemClock.elapsedRealtime()>=task.getLong("deadline_elapsed_ms")){target.close();completion.fail("request_timeout");return;}
                String kind=event.getString("kind");
                var result=switch(kind){
                    case "pointer" -> target.pointer(sequence,event.getInt("action"),(float)event.getDouble("x"),(float)event.getDouble("y"),event.optBoolean("mouse"));
                    case "key" -> target.key(sequence,event.getInt("code"),event.getBoolean("down"));
                    case "cancel" -> {target.close();yield RemoteInputPointerTarget.Result.ACCEPTED;}
                    default -> RemoteInputPointerTarget.Result.INVALID;
                };
                if(result!=RemoteInputPointerTarget.Result.ACCEPTED){target.close();completion.fail("native_unavailable");ScreenCastLog.w("YueJieScreen","input_native_rejected "+result);return;}
                if(!"pointer".equals(kind)||event.optInt("action")!=2)ScreenCastLog.i("YueJieScreen","input_applied "+kind+" seq="+sequence);
                completion.success(new JSONObject().put("stream_id",owned.streamId()).put("sequence",sequence).put("accepted",true));
            }catch(Exception error){if(target!=null)target.close();completion.fail("invalid_payload");ScreenCastLog.e("YueJieScreen","input_execute_failed",error);}
        });
    }
    void revoke() {
        var owned=scope;
        if(owned!=null&&!source&&client.projection()!=null){
            try{client.request(client.projection().optString("id"),owned.sessionId(),"screen_input","input:"+owned.streamId()+":"+(++sent),
                new JSONObject().put("stream_id",owned.streamId()).put("sequence",sent).put("kind","cancel"),r->{});}catch(Exception ignored){}
        }
        main.removeCallbacks(flushMove);pendingMove=null;
        gesture.reset();
        scope=null;current=()->false;
        worker.post(()->{if(target!=null){target.close();target=null;}received=0;});
    }
    @Override public void cancel(String session,String request){var owned=scope;if(owned!=null&&owned.sessionId().equals(session))revoke();}
    @Override public void sessionChanged(){revoke();}
}
