package io.github.qiangjian.connect;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** All Binder calls on the control lane are short. OEM execution/completions run on main. */
final class BridgeClient implements AutoCloseable {
    // Reflective access keeps independent Android-source compilation usable without generated
    // BuildConfig. A real APK always contains it; absence never authenticates a runtime.
    static String runtimeRevision(){
        try{return (String)Class.forName("io.github.qiangjian.connect.BuildConfig",false,BridgeClient.class.getClassLoader()).getField("RUNTIME_INPUT_SHA").get(null);}
        catch(ReflectiveOperationException|RuntimeException unavailable){return "unavailable";}
    }
    interface Subscription extends AutoCloseable { @Override void close(); }
    interface TaskCompletion { void success(JSONObject data); void fail(String code); }
    interface VendorExecutor {
        void execute(JSONObject task, TaskCompletion completion);
        void cancel(String sessionId, String requestId);
        void sessionChanged();
    }
    private volatile IBridge bridge;
    private volatile boolean closed;
    private volatile Context context;
    private volatile JSONObject projection;
    private volatile Consumer<JSONObject> legacyListener;
    private final CopyOnWriteArrayList<Consumer<JSONObject>> listeners=new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MediaPipe> mediaPipes=new CopyOnWriteArrayList<>();
    private final Map<String,VendorExecutor> executors=new LinkedHashMap<>();
    private final Map<String,Pending> requests=new LinkedHashMap<>();
    private final NativeLogicalSessions logical=new NativeLogicalSessions();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean polling=new AtomicBoolean();
    private String token,lastIpcStatus="";
    private long sequence,leaseDeadline,nextOpen,nextBind,nextProjection,nextRegistration;
    private boolean bound,pollStarted;
    private volatile long fileBurstUntil;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"qj-control");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private record Pending(String id,String session,String operation,String request,Consumer<JSONObject> completion,long deadline) {}
    private final Runnable poll=new Runnable(){public void run(){
        if(context!=null&&polling.compareAndSet(false,true)) {
            if(!enqueue(()->{try{tick();}finally{polling.set(false);}}))polling.set(false);
        }
        main.postDelayed(this,SystemClock.elapsedRealtime()<fileBurstUntil?5:100);
    }};
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){bridge=IBridge.Stub.asInterface(binder);enqueue(()->{token=null;nextOpen=0;nextRegistration=0;});}
        @Override public void onServiceDisconnected(ComponentName name){bridge=null;publish(null);}
        @Override public void onBindingDied(ComponentName name){bridge=null;enqueue(()->unbind());}
        @Override public void onNullBinding(ComponentName name){bridge=null;enqueue(()->unbind());}
    };
    private boolean enqueue(Runnable task){
        if(closed)return false;
        try{worker.execute(()->{try{task.run();}catch(RuntimeException failure){token=null;publish(null);android.util.Log.w("QiangJianClient","worker_error "+failure.getClass().getSimpleName());}});return true;}
        catch(java.util.concurrent.RejectedExecutionException busy){return false;}
    }
    void attach(Context value){context=value;enqueue(this::ensureBound);if(!pollStarted){pollStarted=true;main.post(poll);}}
    Context context(){return context;}
    void onProjection(Consumer<JSONObject> listener){legacyListener=listener;if(listener!=null)main.post(()->{if(legacyListener==listener)listener.accept(projection);});}
    Subscription addProjectionListener(Consumer<JSONObject> listener){
        listeners.add(listener);main.post(()->{if(listeners.contains(listener))listener.accept(projection);});
        return ()->listeners.remove(listener);
    }
    JSONObject projection(){return projection;}
    void disconnect(String id,String session,Consumer<JSONObject> completion){
        if(!enqueue(()->{
            try{
                JSONObject response=authenticated(new JSONObject().put("op","bridge_disconnect").put("projection_id",id).put("session_id",session));
                if(response!=null&&"ok".equals(response.optString("status"))){
                    JSONObject live=projection;
                    if(live!=null&&id.equals(live.optString("id"))&&session.equals(live.optString("session_id")))publish(null);
                }
                deliver(completion,response==null?error("service_unavailable","",session):response);
            }catch(org.json.JSONException|RemoteException|RuntimeException failure){deliver(completion,error("service_unavailable","",session));}
        }))deliver(completion,error("busy","",session));
    }
    boolean hasIncomingLogicalChannel(String operation){JSONObject p=projection;return p!=null&&logical.openFor(p.optString("session_id"),operation);}
    /** Completes on main only for an already authenticated, freshly native-approved stream.
     * Null means no pipe was opened. The media protocol, not this descriptor, proves readiness. */
    void openMediaPipe(String id,String session,long stream,String purpose,String role,Consumer<MediaPipe> completion){
        if(stream<=0||!java.util.Set.of("screen_video","camera_video").contains(purpose)||!java.util.Set.of("sender","receiver").contains(role)){
            main.post(()->completion.accept(null));return;
        }
        if(!enqueue(()->{
            MediaPipe pipe=null;
            try{
                JSONObject p=projection;if(p==null||!id.equals(p.optString("id"))||!session.equals(p.optString("session_id")))throw new IllegalStateException();
                JSONObject query=new JSONObject().put("op","bridge_media_status").put("projection_id",id).put("session_id",session).put("stream_id",stream).put("purpose",purpose).put("role",role);
                JSONObject state=authenticated(query);
                if(state!=null&&"ok".equals(state.optString("status"))&&"ready".equals(state.optString("phase"))&&session.equals(state.optString("session_id"))&&stream==state.optLong("stream_id")){
                    IBridge current=bridge;if(current!=null){query.put("op","bridge_media_attach").put("token",token).put("sequence",++sequence);
                        var descriptor=current.openMediaPipe(query.toString());
                        if(descriptor!=null){pipe=new MediaPipe(descriptor,session,stream,purpose,role);mediaPipes.add(pipe);}}
                }
            }catch(org.json.JSONException|RemoteException|RuntimeException failure){if(pipe!=null)pipe.close();pipe=null;}
            MediaPipe value=pipe;main.post(()->{try{completion.accept(value);}catch(RuntimeException failure){if(value!=null)value.close();}});
        }))main.post(()->completion.accept(null));
    }
    /** Local binary socket only. No frame or PSK is serialized into Binder JSON. */
    static final class MediaPipe implements AutoCloseable {
        private final android.os.ParcelFileDescriptor descriptor;
        private final String session,purpose,role;
        private final long stream;
        private final Handler main=new Handler(Looper.getMainLooper());
        private final ThreadPoolExecutor writer;
        private java.io.OutputStream output;
        private Consumer<byte[]> receiver;
        private Runnable onClosed;
        private boolean closed,reading;
        private int frames,bytes;
        private long lastOffered,lastReceived;
        MediaPipe(android.os.ParcelFileDescriptor descriptor,String session,long stream,String purpose,String role){
            this.descriptor=descriptor;this.session=session;this.stream=stream;this.purpose=purpose;this.role=role;
            writer=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(7),r->{Thread t=new Thread(r,"qj-media-write");t.setDaemon(true);return t;});
            if("sender".equals(role))output=new android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
        }
        public boolean offer(byte[] wire){
            Write task;
            synchronized(this){
                if(closed||!"sender".equals(role)||wire==null||wire.length<40||wire.length>1_048_616||frames>=8||bytes+wire.length>4*1_048_576)return false;
                byte[] copy=wire.clone();
                java.nio.ByteBuffer header=java.nio.ByteBuffer.wrap(copy).order(java.nio.ByteOrder.BIG_ENDIAN);
                long sequence=header.getLong(16);
                if(header.getInt(0)!=0x514a5631||header.getShort(4)!=1||header.getLong(8)!=stream||sequence<=lastOffered||header.getInt(32)!=copy.length-40){java.util.Arrays.fill(copy,(byte)0);return false;}
                frames++;bytes+=copy.length;lastOffered=sequence;task=new Write(copy);
            }
            try{writer.execute(task);return true;}catch(java.util.concurrent.RejectedExecutionException rejected){task.wipe();return false;}
        }
        private final class Write implements Runnable {
            private byte[] wire;
            Write(byte[] wire){this.wire=wire;}
            void wipe(){synchronized(MediaPipe.this){if(wire!=null){frames=Math.max(0,frames-1);bytes=Math.max(0,bytes-wire.length);java.util.Arrays.fill(wire,(byte)0);wire=null;}}}
            public void run(){try{byte[] packet=wire;if(packet==null)return;synchronized(MediaPipe.this){if(closed)return;}
                int n=packet.length;output.write(new byte[]{(byte)n,(byte)(n>>>8),(byte)(n>>>16),(byte)(n>>>24)});output.write(packet);output.flush();
            }catch(java.io.IOException|RuntimeException failure){close();}finally{wipe();}}
        }
        public synchronized void setReceiver(Consumer<byte[]> callback){
            if(!"receiver".equals(role)||receiver!=null||callback==null)throw new IllegalStateException("media_receiver_already_bound");
            if(closed)return;receiver=callback;reading=true;
            Thread reader=new Thread(this::read,"qj-media-read");reader.setDaemon(true);reader.start();
        }
        private void read(){
            try(var input=new java.io.DataInputStream(new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor))){
                while(true){int size=Integer.reverseBytes(input.readInt());if(size<40||size>1_048_616)throw new java.io.IOException();
                    byte[] wire=new byte[size];input.readFully(wire);var header=java.nio.ByteBuffer.wrap(wire).order(java.nio.ByteOrder.BIG_ENDIAN);
                    long sequence=header.getLong(16);Consumer<byte[]> callback;
                    synchronized(this){if(closed)return;if(header.getInt(0)!=0x514a5631||header.getShort(4)!=1||header.getLong(8)!=stream||sequence<=lastReceived||header.getInt(32)!=size-40)throw new java.io.IOException();lastReceived=sequence;callback=receiver;}
                    callback.accept(wire);
                }
            }catch(java.io.IOException|RuntimeException failure){close();}
        }
        public void setOnClosed(Runnable callback){boolean done;synchronized(this){if(onClosed!=null)throw new IllegalStateException("media_close_already_bound");onClosed=java.util.Objects.requireNonNull(callback);done=closed;}if(done)main.post(callback);}
        @Override public void close(){Runnable callback;synchronized(this){if(closed)return;closed=true;callback=onClosed;}
            try{android.system.Os.shutdown(descriptor.getFileDescriptor(),android.system.OsConstants.SHUT_RDWR);}catch(android.system.ErrnoException|RuntimeException ignored){}
            try{descriptor.close();}catch(java.io.IOException ignored){}
            for(Runnable queued:writer.shutdownNow())if(queued instanceof Write pending)pending.wipe();
            if(callback!=null)main.post(callback);
        }
    }
    java.util.concurrent.CompletableFuture<MediaPipe> openNativeMedia(String id,String session,long stream,String purpose,String role){
        var result=new java.util.concurrent.CompletableFuture<MediaPipe>();
        if(!enqueue(()->{try{
            JSONObject registered=authenticated(new JSONObject().put("op","bridge_media_register").put("projection_id",id).put("session_id",session)
                .put("stream_id",stream).put("purpose",purpose).put("role",role).put("lifetime_ms",900000));
            if(registered==null||!"ok".equals(registered.optString("status"))){result.completeExceptionally(new IllegalStateException(registered==null?"service_unavailable":registered.optString("code","media_unavailable")));return;}
            long deadline=SystemClock.elapsedRealtime()+8500;
            main.post(new Runnable(){public void run(){
                JSONObject p=projection;
                if(result.isDone()||closed||p==null||!id.equals(p.optString("id"))||!session.equals(p.optString("session_id"))||SystemClock.elapsedRealtime()>=deadline){
                    closeNativeMedia(session,stream);result.completeExceptionally(new IllegalStateException("media_open_cancelled_or_expired"));return;
                }
                openMediaPipe(id,session,stream,purpose,role,pipe->{
                    if(pipe!=null){if(!result.complete(pipe))pipe.close();}
                    else if(!result.isDone())main.postDelayed(this,100);
                });
            }});
        }catch(org.json.JSONException|RemoteException|RuntimeException failure){result.completeExceptionally(failure);}}))result.completeExceptionally(new IllegalStateException("busy"));
        result.whenComplete((pipe,error)->{if(error!=null)closeNativeMedia(session,stream);});return result;
    }
    void closeNativeMedia(String session,long stream){enqueue(()->{try{authenticated(new JSONObject().put("op","bridge_media_close").put("session_id",session).put("stream_id",stream));}catch(org.json.JSONException|RemoteException ignored){}});}
    void allocateFile(String expectedSession,Consumer<JSONObject> completion){
        localFileCommand("bridge_file_allocate",expectedSession,null,null,completion);
    }
    void releaseFile(String expectedSession,String transfer,String direction,Consumer<JSONObject> completion){
        if(!"incoming".equals(direction)&&!"outgoing".equals(direction)){deliver(completion,error("invalid_request","",expectedSession));return;}
        localFileCommand("bridge_file_release",expectedSession,transfer,direction,completion);
    }
    private void localFileCommand(String operation,String session,String transfer,String direction,Consumer<JSONObject> completion){
        if(!enqueue(()->{try{
            if("bridge_file_release".equals(operation)){
                IBridge current=bridge;
                if(current!=null){JSONObject scope=new JSONObject(current.transactJson(new JSONObject().put("op","file_cleanup_state").put("session_id",session).put("runtime_revision",runtimeRevision()).toString()));
                    if(!"ok".equals(scope.optString("status"))){deliver(completion,scope);return;}}
            }
            JSONObject value=new JSONObject().put("op",operation).put("session_id",session);
            if(transfer!=null)value.put("transfer",transfer);
            if(direction!=null)value.put("direction",direction);
            JSONObject reply=authenticated(value);deliver(completion,reply==null?error("service_unavailable","",session):reply);
        }catch(org.json.JSONException|RemoteException|RuntimeException failure){deliver(completion,error("service_unavailable","",session));}}))deliver(completion,error("busy","",session));
    }
    // Initialization may depend on local consent before readiness can be advertised. This is
    // never sufficient to dispatch: request/task routing still requires the bilateral operations.
    boolean hasLocalGrant(String operation){
        JSONObject p=projection;JSONArray grants=p==null?null:p.optJSONArray("local_grants");
        if(grants!=null)for(int i=0;i<grants.length();i++)if(operation.equals(grants.optString(i)))return true;
        return false;
    }
    // These fixed, local Binder operations carry only a one-use request association. They never
    // wait for a network peer, grant capture, or share the authenticated control-lane sequence.
    JSONObject nativeScreenRequest(JSONObject command){
        if(closed||!java.util.Set.of("screen_request_issue","screen_request_claim","screen_request_allowed","screen_request_renew","screen_request_revoke").contains(command.optString("op")))return error("permission_denied","","");
        try{IBridge current=bridge;return current==null?error("service_unavailable","",""):new JSONObject(current.transactJson(new JSONObject(command.toString()).put("runtime_revision",runtimeRevision()).toString()));}
        catch(org.json.JSONException|RemoteException failure){return error("service_unavailable","","");}
    }
    Subscription registerExecutor(String operation,VendorExecutor executor){
        if(!"clipboard_write".equals(operation)&&!"clipboard_rich_write".equals(operation)&&!"device_finder".equals(operation)
                &&!"notification_post".equals(operation)&&!"notification_remove".equals(operation)&&!"notification_action".equals(operation)&&!"app_relay".equals(operation)
                &&!java.util.Set.of("file_prepare","file_offer","file_chunk","file_commit","file_cancel","clipboard_uri_write","media_control",
                    "telephony_call_state","telephony_call_control","telephony_sms_status","screen_control").contains(operation))throw new IllegalArgumentException("Operation not implemented");
        synchronized(executors){if(executors.containsKey(operation))throw new IllegalStateException("Executor already registered");executors.put(operation,executor);}
        enqueue(()->nextRegistration=0);
        return ()->{synchronized(executors){executors.remove(operation,executor);}enqueue(()->nextRegistration=0);main.post(executor::sessionChanged);};
    }
    private void publish(JSONObject value){
        if(closed&&value!=null)return;
        JSONObject previous=projection;
        if(Objects.equals(previous==null?null:previous.toString(),value==null?null:value.toString()))return;
        projection=value;
        for(MediaPipe pipe:mediaPipes){if(value==null||!pipe.session.equals(value.optString("session_id"))||!hasLocalGrant(pipe.purpose)){pipe.close();mediaPipes.remove(pipe);}}
        logical.session(value==null?null:value.optString("session_id"));
        boolean changed=!Objects.equals(previous==null?null:previous.optString("session_id"),value==null?null:value.optString("session_id"));
        main.post(()->{
            if(changed){ArrayList<VendorExecutor> copy;synchronized(executors){copy=new ArrayList<>(executors.values());}for(VendorExecutor executor:copy)try{executor.sessionChanged();}catch(RuntimeException ignored){}}
            Consumer<JSONObject> legacy=legacyListener;if(legacy!=null)try{legacy.accept(value);}catch(RuntimeException failure){android.util.Log.w("QiangJianClient","projection_listener_failed");}
            for(Consumer<JSONObject> listener:listeners)try{listener.accept(value);}catch(RuntimeException failure){android.util.Log.w("QiangJianClient","projection_listener_failed");}
        });
    }
    private void ipcStatus(String status){if(!status.equals(lastIpcStatus)){lastIpcStatus=status;android.util.Log.i("QiangJianClient",status);}}
    private JSONObject authenticated(JSONObject command)throws org.json.JSONException,RemoteException{
        ensureBound();IBridge current=bridge;if(current==null)return null;
        long now=SystemClock.elapsedRealtime();
        if(token==null||now>=leaseDeadline){
            if(now<nextOpen)return null;nextOpen=now+5_000;
            JSONObject opened=new JSONObject(current.transactJson(new JSONObject().put("op","open").put("runtime_revision",runtimeRevision()).toString()));
            if(!"ok".equals(opened.optString("status"))){ipcStatus("lease_rejected "+opened.optString("code"));return null;}
            token=opened.getString("token");sequence=0;leaseDeadline=now+opened.getLong("lease_ms")-1000;nextRegistration=0;ipcStatus("lease_opened");
        }
        // The revision is bound to the returned IPC token at open. Do not add 86 bytes to
        // every content request: the existing rich-clipboard 8192-byte budget is unchanged.
        command.put("token",token).put("sequence",++sequence);
        JSONObject response=new JSONObject(current.transactJson(command.toString()));
        String code=response.optString("code");
        if("invalid_session".equals(code)||"replay".equals(code)||"consent_required".equals(code)||"runtime_mismatch".equals(code)){token=null;ipcStatus("ipc_rejected "+code);}
        return response;
    }
    private void tick(){
        if(closed)return;
        try{
            long now=SystemClock.elapsedRealtime();
            if(now>=nextProjection){nextProjection=now+1000;JSONObject response=authenticated(new JSONObject().put("op","bridge_projections"));publish(response==null?null:response.optJSONObject("projection"));}
            JSONArray operations=new JSONArray();boolean businessReady;synchronized(executors){for(String operation:executors.keySet())operations.put(operation);businessReady=executors.keySet().stream().anyMatch(NativeLogicalSessions.BUSINESS::contains);}
            // The mailbox routes each logical channel to its requested business's process.
            if(businessReady){operations.put("logical_open").put("logical_close").put("logical_state");}
            else if(logical.hasRecords()){operations.put("logical_close").put("logical_state");}
            if(now>=nextRegistration){nextRegistration=now+1000;JSONObject registered=authenticated(new JSONObject().put("op","bridge_register_executor").put("operations",operations));if(registered==null||!"ok".equals(registered.optString("status")))nextRegistration=now+300;}
            if(operations.length()>0){JSONObject reply=authenticated(new JSONObject().put("op","bridge_poll_executor"));if(reply!=null&&"ok".equals(reply.optString("status"))){JSONObject task=reply.optJSONObject("task");JSONArray cancelled=reply.optJSONArray("cancelled");if(cancelled!=null&&cancelled.length()>0)main.post(()->cancelTasks(cancelled));if(task!=null){task.put("deadline_elapsed_ms",SystemClock.elapsedRealtime()+task.getLong("remaining_ms"));main.post(()->executeTask(task));}}}
            for(Pending pending:new ArrayList<>(requests.values())){
                if("file_chunk".equals(pending.operation))fileBurstUntil=now+250;
                JSONObject selected=projection,response;
                if(selected==null||!pending.session.equals(selected.optString("session_id")))response=error("stale_session",pending.request,pending.session);
                else if(now>=pending.deadline){authenticated(new JSONObject().put("op","bridge_cancel").put("session_id",pending.session).put("request_id",pending.request));response=error("request_timeout",pending.request,pending.session);}
                else response=authenticated(new JSONObject().put("op","bridge_result").put("session_id",pending.session).put("request_id",pending.request));
                if(response!=null&&!"pending".equals(response.optString("status"))){requests.remove(pending.request);deliver(pending.completion,response);}
            }
        }catch(org.json.JSONException|RemoteException failure){token=null;publish(null);}
    }
    private void cancelTasks(JSONArray cancelled){
        ArrayList<VendorExecutor> copy;synchronized(executors){copy=new ArrayList<>(executors.values());}
        for(int i=0;i<cancelled.length();i++){JSONArray pair=cancelled.optJSONArray(i);if(pair!=null)for(VendorExecutor executor:copy)try{executor.cancel(pair.optString(0),pair.optString(1));}catch(RuntimeException ignored){}}
    }
    private void executeTask(JSONObject task){
        String operation=task.optString("operation"),session=task.optString("session_id"),request=task.optString("request_id");
        if("file_chunk".equals(operation))fileBurstUntil=SystemClock.elapsedRealtime()+250;
        VendorExecutor executor;synchronized(executors){executor=executors.get(operation);}
        AtomicBoolean completed=new AtomicBoolean();
        TaskCompletion completion=new TaskCompletion(){
            private void finish(JSONObject data,String code){if(!completed.compareAndSet(false,true))return;enqueue(()->{try{JSONObject command=new JSONObject().put("op","bridge_complete_executor").put("session_id",session).put("request_id",request);if(data!=null)command.put("result",new JSONObject().put("operation",operation).put("data",data));else command.put("error",code);authenticated(command);}catch(org.json.JSONException|RemoteException ignored){}});}
            @Override public void success(JSONObject data){if(data==null)fail("invalid_result");else finish(data,null);}
            @Override public void fail(String code){finish(null,code);}
        };
        JSONObject selected=projection;
        if(selected==null||!session.equals(selected.optString("session_id"))||!task.optString("projection_id").equals(selected.optString("id"))){completion.fail("session_expired");return;}
        if(SystemClock.elapsedRealtime()>=task.optLong("deadline_elapsed_ms")){completion.fail("request_timeout");return;}
        if(operation.equals("logical_open")||operation.equals("logical_close")||operation.equals("logical_state")){
            try{
                JSONObject payload=task.getJSONObject("payload");String channel=payload.getString("channel_id");
                java.util.Set<String> available;synchronized(executors){available=new java.util.HashSet<>(executors.keySet());}
                java.util.Set<String> granted=new java.util.HashSet<>();JSONArray values=selected.optJSONArray("operations");
                if(values!=null)for(int i=0;i<values.length();i++)granted.add(values.optString(i));
                available.retainAll(granted);
                NativeLogicalSessions.State state;
                if(operation.equals("logical_open")){JSONArray list=payload.getJSONArray("operations");java.util.Set<String> requested=new java.util.HashSet<>();for(int i=0;i<list.length();i++)requested.add(list.getString(i));state=logical.open(session,channel,requested,available);}
                else if(operation.equals("logical_close"))state=logical.close(session,channel);
                else state=logical.state(session,channel,available);
                completion.success(new JSONObject().put("channel_id",state.channelId()).put("state",state.open()?"open":"closed").put("operations",new JSONArray(state.operations())));
            }catch(org.json.JSONException invalid){completion.fail("invalid_payload");}
            catch(IllegalArgumentException|IllegalStateException rejected){completion.fail(rejected.getMessage());}
            return;
        }
        if(executor==null){completion.fail("executor_unavailable");return;}
        try{executor.execute(task,completion);}catch(RuntimeException failure){completion.fail("remote_error");}
    }
    void request(String id,String operation,String requestId,JSONObject payload,Consumer<JSONObject> completion){
        JSONObject selected=projection;
        request(id,selected==null?"":selected.optString("session_id"),operation,requestId,payload,completion);
    }
    void request(String id,String session,String operation,String requestId,JSONObject payload,Consumer<JSONObject> completion){
        JSONObject selected=projection;
        if(selected==null||!id.equals(selected.optString("id"))){deliver(completion,error("peer_offline",requestId,session));return;}
        if(session==null||!session.equals(selected.optString("session_id"))){deliver(completion,error("stale_session",requestId,session));return;}
        String copied=payload==null?"{}":payload.toString();
        if(!enqueue(()->{
            if(requests.size()>=32||requests.containsKey(requestId)){deliver(completion,error("busy",requestId,session));return;}
            try{JSONObject response=authenticated(new JSONObject().put("op","bridge_submit").put("projection_id",id).put("session_id",session).put("request_id",requestId).put("operation",operation).put("payload",new JSONObject(copied)));
                if(response!=null&&"pending".equals(response.optString("status"))){requests.put(requestId,new Pending(id,session,operation,requestId,completion,SystemClock.elapsedRealtime()+requestBudget(operation)));if("file_chunk".equals(operation))fileBurstUntil=SystemClock.elapsedRealtime()+250;}
                else deliver(completion,response==null?error("service_unavailable",requestId,session):response);
            }catch(org.json.JSONException|RemoteException|RuntimeException failure){deliver(completion,error("service_unavailable",requestId,session));}
        }))deliver(completion,error("busy",requestId,session));
    }
    private static long requestBudget(String operation){return switch(operation){case "file_commit"->16_500;case "app_relay"->21_500;case "network_share"->36_500;case "telephony_call_state","telephony_call_control","telephony_sms_status"->11_500;default->6500;};}
    void cancel(String session,String requestId,Consumer<JSONObject> completion){
        if(!enqueue(()->{try{JSONObject response=authenticated(new JSONObject().put("op","bridge_cancel").put("session_id",session).put("request_id",requestId));deliver(completion,response==null?error("service_unavailable",requestId,session):response);}catch(org.json.JSONException|RemoteException|RuntimeException failure){deliver(completion,error("service_unavailable",requestId,session));}}))deliver(completion,error("busy",requestId,session));
    }
    JSONObject requestBlocking(String id,String operation,String requestId,JSONObject payload){
        JSONObject selected=projection;
        return requestBlocking(id,selected==null?"":selected.optString("session_id"),operation,requestId,payload);
    }
    @Override public void close(){
        if(closed)return;
        for(MediaPipe pipe:mediaPipes)pipe.close();mediaPipes.clear();
        main.removeCallbacks(poll);
        enqueue(()->{
            try{authenticated(new JSONObject().put("op","bridge_register_executor").put("operations",new JSONArray()));}
            catch(org.json.JSONException|RemoteException|RuntimeException ignored){}
            for(Pending pending:new ArrayList<>(requests.values()))deliver(pending.completion,error("session_expired",pending.request,pending.session));
            requests.clear();unbind();
        });
        closed=true;publish(null);
        synchronized(executors){executors.clear();}
        listeners.clear();legacyListener=null;worker.shutdown();
    }
    JSONObject requestBlocking(String id,String session,String operation,String requestId,JSONObject payload){
        if(Looper.myLooper()==Looper.getMainLooper())return error("would_block_main",requestId,"");
        java.util.concurrent.CompletableFuture<JSONObject> future=new java.util.concurrent.CompletableFuture<>();
        request(id,session,operation,requestId,payload,future::complete);
        try{return future.get(requestBudget(operation)+500,TimeUnit.MILLISECONDS);}
        catch(InterruptedException failure){Thread.currentThread().interrupt();cancel(session,requestId,ignored->{});return error("cancelled",requestId,session);}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException failure){cancel(session,requestId,ignored->{});return error("request_timeout",requestId,session);}
    }
    private void deliver(Consumer<JSONObject> completion,JSONObject response){main.post(()->{try{completion.accept(response);}catch(RuntimeException failure){android.util.Log.w("QiangJianClient","completion_listener_failed");}});}
    private static JSONObject error(String code,String request,String session){try{return new JSONObject().put("status","error").put("code",code).put("request_id",request).put("session_id",session);}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}}
    void queryRealPeer(String id,Consumer<JSONObject> completion){
        request(id,"device_profile","profile:"+java.util.UUID.randomUUID(),new JSONObject(),response->{try{JSONObject result=response.optJSONObject("result");if("ok".equals(response.optString("status"))&&result!=null)completion.accept(new JSONObject().put("status","ok").put("real_device",result.getJSONObject("data")).put("native_connection_supported",false));else completion.accept(response);}catch(org.json.JSONException invalid){completion.accept(null);}});
    }
    void offer(JSONObject event){
        if(event.optString("native_id").startsWith("qj:"))return;
        long observedAt=SystemClock.elapsedRealtime();String copied=event.toString();
        enqueue(()->{if(SystemClock.elapsedRealtime()-observedAt>5000)return;try{authenticated(new JSONObject().put("op","observe").put("event",new JSONObject(copied)));}catch(org.json.JSONException|RemoteException failure){token=null;}});
    }
    private void unbind(){if(bound){bound=false;try{context.unbindService(connection);}catch(IllegalArgumentException ignored){}}token=null;publish(null);}
    private void ensureBound(){
        if(context==null||bound||SystemClock.elapsedRealtime()<nextBind)return;
        nextBind=SystemClock.elapsedRealtime()+10_000;
        try{bound=context.bindService(new Intent().setComponent(new ComponentName("io.github.qiangjian.connect","io.github.qiangjian.connect.BridgeService")),connection,Context.BIND_AUTO_CREATE);}catch(SecurityException ignored){bound=false;}
    }
}
