package io.github.qiangjian.connect;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Bounded, lossy observer. Overload must never block a vendor thread. */
final class BridgeClient {
    private volatile IBridge bridge;
    private volatile Context context;
    private String token;
    private long sequence, leaseDeadline, nextOpen, nextBind;
    private boolean bound;
    private String lastIpcStatus="";
    private volatile Consumer<JSONObject> projectionListener;
    private volatile JSONObject projection;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean polling=new AtomicBoolean();
    private boolean pollStarted;
    private final Runnable poll=new Runnable(){public void run(){
        if(context!=null && polling.compareAndSet(false,true)){
            if(worker.getQueue().size()<8){if(!enqueue(()->{try{JSONObject response=authenticated(new JSONObject().put("op","bridge_projections"));
                publish(response==null?null:response.optJSONObject("projection"));}catch(Exception ignored){publish(null);}finally{polling.set(false);}}))polling.set(false);}
            else polling.set(false);
        }
        main.postDelayed(this,1000);
    }};
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), r->{Thread t=new Thread(r,"qj-observer");t.setDaemon(true);return t;},
            new ThreadPoolExecutor.AbortPolicy());
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n,IBinder b) { bridge=IBridge.Stub.asInterface(b);enqueue(()->{token=null;nextOpen=0;}); }
        @Override public void onServiceDisconnected(ComponentName n) { bridge=null;publish(null); }
        @Override public void onBindingDied(ComponentName n) { bridge=null;enqueue(()->unbind()); }
        @Override public void onNullBinding(ComponentName n) { bridge=null;enqueue(()->unbind()); }
    };
    private boolean enqueue(Runnable task) {
        try { worker.execute(()->{try{task.run();}catch(RuntimeException error){token=null;publish(null);android.util.Log.w("QiangJianClient","worker_error "+error.getClass().getSimpleName());}}); return true; }
        catch(java.util.concurrent.RejectedExecutionException ignored) { return false; }
    }
    private void ipcStatus(String status){if(!status.equals(lastIpcStatus)){lastIpcStatus=status;android.util.Log.i("QiangJianClient",status);}}
    void attach(Context ctx) { context=ctx;enqueue(this::ensureBound);if(!pollStarted){pollStarted=true;main.post(poll);} }
    void onProjection(Consumer<JSONObject> listener){projectionListener=listener;if(listener!=null)main.post(()->{if(projectionListener==listener)listener.accept(projection);});}
    JSONObject projection(){return projection;}
    private void publish(JSONObject value){
        JSONObject previous=projection;
        if(java.util.Objects.equals(previous==null?null:previous.toString(),value==null?null:value.toString()))return;
        projection=value;Consumer<JSONObject> listener=projectionListener;
        if(listener!=null)main.post(()->{if(projectionListener==listener)listener.accept(value);});
    }
    private JSONObject authenticated(JSONObject command)throws org.json.JSONException,RemoteException{
        ensureBound();IBridge current=bridge;if(current==null)return null;
        long now=SystemClock.elapsedRealtime();
        if(token==null || now>=leaseDeadline){
            if(now<nextOpen)return null;nextOpen=now+5_000;
            JSONObject opened=new JSONObject(current.transactJson("{\"op\":\"open\"}"));
            if(!"ok".equals(opened.optString("status"))){ipcStatus("lease_rejected "+opened.optString("code"));return null;}
            ipcStatus("lease_opened");
            token=opened.getString("token");sequence=0;leaseDeadline=now+opened.getLong("lease_ms")-1000;
        }
        command.put("token",token).put("sequence",++sequence);
        JSONObject response=new JSONObject(current.transactJson(command.toString()));
        if(!"ok".equals(response.optString("status"))){
            String code=response.optString("code");
            if(!response.has("request_id"))token=null;
            ipcStatus("ipc_rejected "+code);
        }
        return response;
    }
    /** Single-shot typed operation. Completion on main; cancellation never implies remote rollback. */
    void request(String id,String operation,String requestId,JSONObject payload,Consumer<JSONObject> result){
        JSONObject selected=projection;
        String session=selected==null?"":selected.optString("session_id");
        if(selected==null||!id.equals(selected.optString("id"))){main.post(()->result.accept(error("peer_offline",requestId,session)));return;}
        String copied=payload==null?"{}":payload.toString();
        if(!enqueue(()->{
            JSONObject response;
            try {
                JSONObject now=projection;
                if(now==null||!session.equals(now.optString("session_id")))response=error("stale_session",requestId,session);
                else response=authenticated(new JSONObject().put("op","bridge_operation").put("projection_id",id)
                    .put("session_id",session).put("request_id",requestId).put("operation",operation).put("payload",new JSONObject(copied)));
            } catch(org.json.JSONException|RemoteException|RuntimeException failure){response=null;}
            JSONObject value=response==null?error("service_unavailable",requestId,session):response;
            main.post(()->result.accept(value));
        }))main.post(()->result.accept(error("busy",requestId,session)));
    }
    /** For OEM methods that themselves synchronously wait for a connection/result. Never on main. */
    JSONObject requestBlocking(String id,String operation,String requestId,JSONObject payload){
        if(Looper.myLooper()==Looper.getMainLooper())return error("would_block_main",requestId,"");
        JSONObject selected=projection;
        String session=selected==null?"":selected.optString("session_id");
        if(selected==null||!id.equals(selected.optString("id")))return error("peer_offline",requestId,session);
        java.util.concurrent.CompletableFuture<JSONObject> result=new java.util.concurrent.CompletableFuture<>();
        String copied=payload==null?"{}":payload.toString();
        if(!enqueue(()->{
            if(result.isDone())return;
            try {
                JSONObject now=projection;
                if(now==null||!session.equals(now.optString("session_id"))){result.complete(error("stale_session",requestId,session));return;}
                result.complete(authenticated(new JSONObject().put("op","bridge_operation").put("projection_id",id)
                    .put("session_id",session).put("request_id",requestId).put("operation",operation).put("payload",new JSONObject(copied))));
            }catch(Exception failure){result.complete(error("service_unavailable",requestId,session));}
        }))return error("busy",requestId,session);
        try{JSONObject response=result.get(7,TimeUnit.SECONDS);return response==null?error("service_unavailable",requestId,session):response;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();result.cancel(false);return error("cancelled",requestId,session);}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException failure){result.cancel(false);return error("request_timeout",requestId,session);}
    }
    private static JSONObject error(String code,String request,String session){
        try{return new JSONObject().put("status","error").put("code",code).put("request_id",request).put("session_id",session);}
        catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}
    }
    void queryRealPeer(String id,Consumer<JSONObject> result){
        if(!enqueue(()->{
            JSONObject response=null;
            try{response=authenticated(new JSONObject().put("op","bridge_profile").put("projection_id",id));}
            catch(org.json.JSONException|RemoteException|RuntimeException ignored){token=null;}
            JSONObject value=response;main.post(()->result.accept(value));
        }))main.post(()->result.accept(null));
    }
    private void unbind() {
        if(bound) {
            bound=false; // onBindingDied can follow framework-side cleanup; never unbind twice.
            try{context.unbindService(connection);}catch(IllegalArgumentException alreadyRemoved){android.util.Log.i("QiangJianClient","binding_already_removed");}
        }
        token=null;
        publish(null);
    }
    private void ensureBound() {
        if(context==null || bound || SystemClock.elapsedRealtime()<nextBind) return;
        nextBind=SystemClock.elapsedRealtime()+10_000;
        Intent i=new Intent().setComponent(new ComponentName("io.github.qiangjian.connect","io.github.qiangjian.connect.BridgeService"));
        try { bound=context.bindService(i,connection,Context.BIND_AUTO_CREATE); }
        catch(SecurityException ignored) { bound=false; }
    }
    void offer(JSONObject event) {
        if(event.optString("native_id").startsWith("qj:"))return; // Never feed our own projection back as a physical discovery.
        // No vendor object or Xposed Chain escapes the callback; only copied scalar values.
        final long observedAt=SystemClock.elapsedRealtime();
        enqueue(()->{
            if(SystemClock.elapsedRealtime()-observedAt>5_000) return;
            ensureBound(); IBridge current=bridge;
            if(current==null) return;
            try {
                long now=SystemClock.elapsedRealtime();
                if(token==null || now>=leaseDeadline) {
                    if(now<nextOpen) return;
                    nextOpen=now+5_000;
                    JSONObject opened=new JSONObject(current.transactJson("{\"op\":\"open\"}"));
                    if(!"ok".equals(opened.optString("status"))) return;
                    token=opened.getString("token");sequence=0;leaseDeadline=now+opened.getLong("lease_ms")-1000;
                }
                JSONObject command=new JSONObject().put("op","observe").put("token",token)
                        .put("sequence",++sequence).put("event",event);
                JSONObject result=new JSONObject(current.transactJson(command.toString()));
                if(!"ok".equals(result.optString("status"))) token=null;
            } catch(RemoteException e) { bridge=null;token=null; }
            catch(org.json.JSONException | RuntimeException ignored) { token=null; }
        });
    }
}
