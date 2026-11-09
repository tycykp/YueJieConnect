package io.github.yuejie.connect;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.Toast;
import io.github.libxposed.api.XposedModule;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.json.*;

/** Runtime owner of real OEM screen gestures, typed drag control, and authenticated file bytes. */
final class ScreenDragRuntime implements BridgeClient.VendorExecutor {
    private record Local(Uri uri,String name,String mime,long size){}
    private static final class Source {
        final String id;
        final ScreenCastSession.Scope scope;
        final List<Local> files;
        final CopyOnWriteArrayList<FileTransferEngine.Key> keys=new CopyOnWriteArrayList<>();
        final Runnable cleanup;
        final AtomicBoolean cleaned=new AtomicBoolean();
        final List<CompletableFuture<?>> pending=new CopyOnWriteArrayList<>();
        volatile boolean cancelled;
        Source(ScreenCastSession.Scope scope,List<Local> files,Runnable cleanup){this(UUID.randomUUID().toString().replace("-",""),scope,files,cleanup);}
        Source(String id,ScreenCastSession.Scope scope,List<Local> files,Runnable cleanup){this.id=id;this.scope=scope;this.files=files;this.cleanup=cleanup;}
    }
    private static final class PhoneGesture {
        final String task;
        final ScreenCastSession.Scope scope;
        final String controlId=UUID.randomUUID().toString().replace("-","");
        volatile boolean ended,handoffRequested;
        volatile Source source;
        OplusDragCapture capture;
        PhoneGesture(String task,ScreenCastSession.Scope scope){this.task=task;this.scope=scope;}
    }
    private static final class Incoming {
        final FileTransferNegotiation.Prepared prepared;
        final ScreenCastSession.Scope scope;
        final CompletableFuture<Uri> local=new CompletableFuture<>();
        volatile FileTransferReceiveLease<Uri> lease;
        volatile boolean selected,receiving,revoked;
        volatile boolean preparing;
        volatile Uri downloadUri;
        volatile boolean downloadCommitted;
        volatile boolean disposed;
        volatile boolean grantsRevoked;
        CompletableFuture<Void> cleanupWork;
        Incoming(FileTransferNegotiation.Prepared p,ScreenCastSession.Scope scope){prepared=p;this.scope=scope;}
    }
    private record LocalGesture(Object view,long session,ScreenCastSession.Scope scope,List<Local> files){}
    private record RemoteGesture(String id,ScreenCastSession.Scope scope,View view,Object handler,List<Incoming> files){}
    private static final class ControlWork {
        final JSONObject task;
        final BridgeClient.TaskCompletion completion;
        final String key;
        boolean done;
        ControlWork(JSONObject task,BridgeClient.TaskCompletion completion){
            this.task=task;this.completion=completion;key=task.optString("session_id")+":"+task.optString("request_id");
        }
    }
    private final Context context;
    private final ClassLoader loader;
    private final BridgeClient client;
    private final FileTransferBridge files;
    private final int brand;
    private final Supplier<ScreenCastSinkRuntime> sink;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"yj-screen-files");t.setDaemon(true);return t;});
    private final Map<String,Source> outgoing=new ConcurrentHashMap<>();
    private final Map<String,Incoming> incoming=new ConcurrentHashMap<>();
    private final Map<Object,LocalGesture> gestures=new IdentityHashMap<>();
    private final Map<String,String> used=new HashMap<>();
    private final Map<String,ControlWork> controls=new HashMap<>();
    private volatile ScreenCastSession.Scope phoneScope;
    private String tableSession;
    private ScreenDragProvider provider;
    private volatile RemoteGesture remote;
    private PhoneGesture phoneGesture;
    private Object sysDragManager,sysDragListener;

    ScreenDragRuntime(XposedModule module,Context context,ClassLoader loader,BridgeClient client,FileTransferBridge files,
            int brand,Supplier<ScreenCastSinkRuntime> sink)throws Exception{
        this.context=context;this.loader=loader;this.client=client;this.files=files;this.brand=brand;this.sink=sink;
        if(brand==1)installPad(module);else installPhone();
        files.registerReceiver(FileTransferNegotiation.Purpose.DRAG,new FileTransferBridge.NativeReceiver(){
            public void prepared(FileTransferNegotiation.Prepared p,FileTransferEndpoint endpoint,FileTransferNegotiation negotiation){
                var scope=scope();if(scope==null||!scope.sessionId().equals(p.key().session())){negotiation.revoke(p.key());return;}
                incoming.putIfAbsent(p.key().transfer(),new Incoming(p,scope));
                log("prepared transfer="+p.key().transfer());
            }
            public void revoked(FileTransferNegotiation.Prepared p){Incoming item=incoming.get(p.key().transfer());if(item!=null)revoke(item);}
        });
        client.registerExecutor("screen_drag",this);
        main.post(new Runnable(){public void run(){tick();main.postDelayed(this,500);}});
        log("runtime_installed brand="+brand);
    }
    private ScreenCastSession.Scope scope(){
        if(brand==2)return phoneScope;
        ScreenCastSinkRuntime runtime=sink.get();return runtime==null?null:runtime.dragScope();
    }
    private boolean current(ScreenCastSession.Scope scope){
        JSONObject p=client.projection();return scope!=null&&scope.equals(scope())&&p!=null&&scope.sessionId().equals(p.optString("session_id"))
            &&client.hasLocalGrant("screen_drag")&&client.hasLocalGrant("file_prepare");
    }
    private boolean current(Incoming item){return item!=null&&!item.revoked&&current(item.scope)&&files.current(item.prepared.key());}
    private boolean current(Source source){return !source.cancelled&&current(source.scope);}
    private void tick(){try{
        JSONObject p=client.projection();String session=p==null?null:p.optString("session_id");
        if(brand==2){
            long stream=0;if(session!=null)stream=client.nativeScreenRequest(new JSONObject().put("op","screen_request_active").put("session_id",session)).optLong("stream_id");
            phoneScope=stream>0?new ScreenCastSession.Scope(session,stream):null;
        }
        if(!Objects.equals(tableSession,session)){tableSession=session;used.clear();}
        for(Source source:outgoing.values())if(!current(source))cancel(source);
        for(Incoming item:incoming.values())if(!current(item))revoke(item);
        if(remote!=null&&!current(remote.scope)){RemoteGesture old=remote;remote=null;old.view.cancelDragAndDrop();}
        if(phoneGesture!=null&&!current(phoneGesture.scope))endPhoneGesture(phoneGesture);
    }catch(Exception e){log("tick_failed "+e.getClass().getSimpleName());}}
    private void installPad(XposedModule module)throws Exception{
        provider=new ScreenDragProvider(module,context,loader,new ScreenDragProvider.Access(){
            public boolean current(FileTransferNegotiation.Prepared p){return ScreenDragRuntime.this.current(incoming.get(p.key().transfer()));}
            public CompletableFuture<Uri> receive(FileTransferNegotiation.Prepared p){return receiveTemporary(incoming.get(p.key().transfer()));}
        });
        Class<?> handler=type("k4.R0");Field nativeView=field(handler,"a");
        module.hook(handler.getMethod("onDrag",View.class,DragEvent.class)).setId("yj.drag.remote.window").intercept(chain->{
            DragEvent event=(DragEvent)chain.getArg(1);RemoteGesture active=remote;
            if(active!=null&&chain.getThisObject()==active.handler&&event.getLocalState()==active.handler){
                if(event.getAction()==DragEvent.ACTION_DROP){control(active.scope,active.id,"cancel",List.of(),null);for(Incoming item:active.files)revoke(item);}
                if(event.getAction()==DragEvent.ACTION_DRAG_ENDED){
                    boolean accepted=event.getResult();log("pad_native_drag_end accepted="+accepted);
                    // Some OEM file managers accept the native save gesture without
                    // opening its URI. Commit that accepted file to the visible inbox;
                    // a later provider read shares this same verified destination.
                    if(accepted)for(Incoming item:active.files)if(current(item))receiveDownload(item);
                    control(active.scope,active.id,accepted?"finish":"cancel",List.of(),null);
                    if(!accepted)for(Incoming item:active.files)revoke(item);
                    remote=null;
                }return true;
            }
            Object view=nativeView.get(chain.getThisObject());
            ScreenCastSession.Scope owned=scope();
            if(sink.get()!=null&&sink.get().dragView()==view&&current(owned)){
                if(event.getLocalState()==chain.getThisObject())return true;
                if(event.getAction()==DragEvent.ACTION_DROP)return acceptPadDrop(event,owned);
                if(event.getAction()==DragEvent.ACTION_DRAG_STARTED){
                    ClipData clip=event.getClipData();
                    return clip!=null&&clip.getItemCount()>0&&clip.getItemCount()<=4;
                }
                return true;
            }
            // OEM's toast is tied to entering its window, not an actual file receipt.
            if(event.getAction()==DragEvent.ACTION_DRAG_ENDED&&sink.get()!=null&&nativeView.get(chain.getThisObject())==sink.get().dragView())field(handler,"i").setInt(chain.getThisObject(),0);
            return chain.proceed();
        });
    }
    private boolean acceptPadDrop(DragEvent event,ScreenCastSession.Scope owned){
        DragAndDropPermissions permission=null;
        try{
            ClipData clip=event.getClipData();if(clip==null||clip.getItemCount()<1||clip.getItemCount()>4)return false;
            for(int i=0;i<clip.getItemCount();i++)FileTransferUris.requireContent(clip.getItemAt(i).getUri());
            permission=(DragAndDropPermissions)DragAndDropPermissions.class.getMethod("obtain",DragEvent.class).invoke(null,event);
            if(permission==null)return false;
            if(Boolean.FALSE.equals(DragAndDropPermissions.class.getMethod("takeTransient").invoke(permission))){permission.release();return false;}
            final DragAndDropPermissions grant=permission;
            ClipData captured=new ClipData(clip);
            Runnable release=()->main.post(grant::release);
            worker.execute(()->{try{
                List<Local> local=localFiles(captured);
                main.post(()->{if(!current(owned)){release.run();return;}begin(new Source(owned,local,release),"drop");});
            }catch(Exception error){release.run();log("pad_drop_source_failed "+error.getClass().getSimpleName());toast("无法读取拖拽文件");}});
            log("pad_drop_captured count="+captured.getItemCount());return true;
        }catch(Exception failed){if(permission!=null)permission.release();log("pad_drop_rejected "+failed.getClass().getSimpleName());return false;}
    }
    private void installPhone()throws Exception{
        // Register a separate real framework listener. The OEM business dispatcher
        // resets during DataBus initialization; our file route does not use DataBus.
        Class<?> manager=type("com.oplus.globaldrag.DragAndDropDispatcherManager");
        Class<?> callback=type("com.oplus.globaldrag.DragAndDropDispatcherManager$IDragAndDropListenerCallback");
        sysDragManager=manager.getMethod("getInstance").invoke(null);
        java.util.concurrent.atomic.AtomicReference<String> nativeTask=new java.util.concurrent.atomic.AtomicReference<>();
        sysDragListener=Proxy.newProxyInstance(loader,new Class<?>[]{callback},(proxy,method,args)->{
            if(method.getName().equals("prePerformDrag")){
                ClipData clip=(ClipData)args[3];var owned=scope();
                String source=(String)args[0];String task=UUID.randomUUID().toString();nativeTask.set(task);
                log("phone_drag_callback current="+current(owned)+" files="+(clip==null?0:clip.getItemCount()));
                if(clip!=null&&current(owned)&&(source==null||!source.contains("com.oplus.linker"))){
                    ClipData captured=new ClipData(clip);
                    main.post(()->startPhoneGesture(task,owned,captured));
                }
            }else if(method.getName().equals("postEndDrag")||method.getName().equals("postCancelDragAndDrop")){
                String task=nativeTask.getAndSet(null);main.post(()->{
                    PhoneGesture gesture=phoneGesture;
                    if(task!=null&&gesture!=null&&gesture.task.equals(task)&&!gesture.handoffRequested)endPhoneGesture(gesture);
                });
            }
            return ScreenCastLinkerRuntime.objectMethod(proxy,method.getName(),args);
        });
        manager.getMethod("registerDragAndDropListener",String.class,callback).invoke(sysDragManager,context.getPackageName(),sysDragListener);
        log("phone_framework_drag_listener_registered");
    }
    private void startPhoneGesture(String task,ScreenCastSession.Scope owned,ClipData clip){
        if(!current(owned))return;
        if(phoneGesture!=null){
            if(phoneGesture.task.equals(task)&&phoneGesture.scope.equals(owned))return;
            endPhoneGesture(phoneGesture);
        }
        PhoneGesture gesture=new PhoneGesture(task,owned);phoneGesture=gesture;
        try{
            gesture.capture=new OplusDragCapture(context,clip,
                ()->control(owned,gesture.controlId,"capture",List.of(),ok->{if(!ok)endPhoneGesture(gesture);}),
                (captured,release)->{
                    gesture.handoffRequested=true;
                    preparePhoneGesture(gesture,captured,release);
                },()->endPhoneGesture(gesture));
        }catch(RuntimeException failed){endPhoneGesture(gesture);log("phone_capture_failed "+failed.getClass().getSimpleName());}
    }
    private void preparePhoneGesture(PhoneGesture gesture,ClipData clip,Runnable release){
        var owned=gesture.scope;
        try{worker.execute(()->{try{
            List<Local> local=localFiles(clip);
            main.post(()->{
                if(phoneGesture!=gesture||gesture.ended||!current(owned)){release.run();return;}
                Source source=new Source(gesture.controlId,owned,local,()->main.post(()->{release.run();if(phoneGesture==gesture)phoneGesture=null;}));
                gesture.source=source;begin(source,"start");
            });
        }catch(Exception error){main.post(()->endPhoneGesture(gesture));log("phone_source_failed "+error.getClass().getSimpleName());}});}
        catch(RejectedExecutionException busy){endPhoneGesture(gesture);toast("文件传输繁忙");}
    }
    private void endPhoneGesture(PhoneGesture gesture){
        gesture.ended=true;if(phoneGesture==gesture)phoneGesture=null;
        if(gesture.capture!=null)gesture.capture.close();
        if(gesture.source!=null)cancel(gesture.source);
    }
    private List<Local> localFiles(ClipData clip)throws Exception{
        if(clip.getItemCount()<1||clip.getItemCount()>4)throw new IOException("file_count_limit");
        List<Local> result=new ArrayList<>();
        for(int i=0;i<clip.getItemCount();i++){
            Uri uri=clip.getItemAt(i).getUri();FileTransferUris.requireContent(uri);String name=null;long size=-1;
            try(Cursor c=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                if(c!=null&&c.moveToFirst()){name=c.getString(0);if(!c.isNull(1))size=c.getLong(1);}
            }
            if(name==null)throw new IOException("file_metadata_missing");
            result.add(new Local(uri,name,mime(uri,name),size));
        }return List.copyOf(result);
    }
    private String mime(Uri uri,String name){
        String value=context.getContentResolver().getType(uri);if(value!=null&&!value.isBlank())return value;
        String extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        value=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);return value==null?"application/octet-stream":value;
    }
    private void begin(Source source,String action){
        if(!current(source)||!files.ready()||outgoing.size()>=4){cleanupSource(source);toast("当前投屏文件通道未就绪");return;}
        outgoing.put(source.id,source);toast(action.equals("drop")?"正在传输到手机…":"正在准备拖出文件…");
        try{worker.execute(()->{
            List<CompletableFuture<FileTransferEngine.Receipt>> sends=new ArrayList<>();
            try{
                for(Local local:source.files){
                    if(!current(source))throw new IOException("stale_session");
                    String token=UUID.randomUUID().toString().replace("-","");
                    var allocation=files.allocate(FileTransferNegotiation.Purpose.DRAG,token);
                    // A timeout/cancel must also release an allocator reply arriving later.
                    allocation.whenComplete((key,error)->{if(key!=null){source.keys.addIfAbsent(key);if(!current(source))files.release(key);}});
                    FileTransferEngine.Key key;
                    try{key=allocation.get(5,TimeUnit.SECONDS);}catch(Exception error){source.cancelled=true;throw error;}
                    source.keys.addIfAbsent(key);
                    if(!current(source)){files.release(key);throw new IOException("file_cancelled");}
                    FileTransferSource snapshot=FileTransferUris.snapshot(context,local.uri,local.name,local.mime,local.size,k->current(source),key);
                    log("source_snapshot transfer="+key.transfer()+" size="+snapshot.offer().size()+" sha256="+snapshot.offer().sha256());
                    var sent=files.send(key,snapshot,k->current(source));sends.add(sent);source.pending.add(sent);
                    if(!current(source))sent.cancel(true);
                }
                main.post(()->{
                    if(!current(source)){cancel(source);return;}
                    if(action.equals("start")){
                        PhoneGesture gesture=phoneGesture;
                        if(gesture==null||gesture.source!=source||gesture.ended){cancel(source);return;}
                        gesture.handoffRequested=true;
                    }
                    control(source.scope,source.id,action,source.keys.stream().map(FileTransferEngine.Key::transfer).toList(),ok->{if(!ok)cancel(source);});
                });
                CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).whenComplete((v,error)->{
                    if(error==null&&!source.cancelled){log("send_committed drag="+source.id);toast("文件传输完成");}
                    else if(!source.cancelled){log("send_failed "+error);toast("文件传输失败");}
                    outgoing.remove(source.id,source);for(var key:source.keys)files.release(key);cleanupSource(source);
                });
            }catch(Exception e){log("source_failed "+e);cancel(source);toast("无法读取或发送拖拽文件");}
        });}catch(RejectedExecutionException busy){cancel(source);toast("文件传输繁忙");}
    }
    private void cancel(Source source){
        source.cancelled=true;outgoing.remove(source.id,source);
        for(var pending:source.pending)pending.cancel(true);
        for(var key:source.keys){files.cancel(key);files.release(key);}cleanupSource(source);
    }
    private void cleanupSource(Source source){if(source.cleaned.compareAndSet(false,true))source.cleanup.run();}
    private void control(ScreenCastSession.Scope scope,String id,String action,List<String> transfers,java.util.function.Consumer<Boolean> done){try{
        JSONObject p=client.projection();if(!current(scope)||p==null){if(done!=null)done.accept(false);return;}
        client.request(p.getString("id"),scope.sessionId(),"screen_drag","drag:"+id+":"+action,
            new JSONObject().put("stream_id",scope.streamId()).put("drag_id",id).put("action",action).put("transfers",new JSONArray(transfers)),reply->{
                boolean ok="ok".equals(reply.optString("status"));log("control_"+action+" ok="+ok+" code="+reply.optString("code"));if(done!=null)done.accept(ok);
            });
    }catch(Exception e){if(done!=null)done.accept(false);}}
    public void execute(JSONObject task,BridgeClient.TaskCompletion completion){main.post(()->{
        ControlWork work=new ControlWork(task,completion);
        if(controls.size()>=16||controls.putIfAbsent(work.key,work)!=null){completion.fail("busy");return;}
        apply(work);
    });}
    private void apply(ControlWork work){
      if(work.done||controls.get(work.key)!=work)return;
      JSONObject task=work.task;
      List<Incoming> selected=new ArrayList<>();
      try{
        JSONObject payload=task.getJSONObject("payload"),p=client.projection();var owned=scope();
        if(!current(owned)||p==null||!owned.sessionId().equals(task.getString("session_id"))||!p.getString("id").equals(task.getString("projection_id"))||owned.streamId()!=payload.getLong("stream_id"))throw new IOException("stale_session");
        if(SystemClock.elapsedRealtime()>=task.getLong("deadline_elapsed_ms"))throw new IOException("request_timeout");
        String id=payload.getString("drag_id"),action=payload.getString("action");
        if(action.equals("capture")){
            if(brand!=1||sink.get()==null||used.size()>=256)throw new IOException("invalid_drag_direction");
            String captureKey="capture:"+id;
            if(!used.containsKey(captureKey)){
                if(!sink.get().captureDrag(owned))throw new IOException("drag_pointer_released");
                used.put(captureKey,Long.toString(owned.streamId()));
            }
            complete(payload,work);return;
        }
        if(action.equals("cancel")||action.equals("finish")){
            if(action.equals("cancel")){
                if(!used.containsKey(id)&&used.size()>=256)throw new IOException("drag_limit");
                used.put(id,"cancelled");
                for(ControlWork waiting:new ArrayList<>(controls.values()))if(waiting!=work){
                    JSONObject data=waiting.task.optJSONObject("payload");
                    if(data!=null&&id.equals(data.optString("drag_id")))fail(waiting,"file_cancelled");
                }
            }
            Source source=outgoing.get(id);if(source!=null&&action.equals("cancel"))cancel(source);
            RemoteGesture active=remote;
            if(active!=null&&active.id.equals(id)&&action.equals("cancel")){
                remote=null;active.view.cancelDragAndDrop();for(Incoming item:active.files)revoke(item);
            }
            complete(payload,work);return;
        }
        String identity=payload.getLong("stream_id")+":"+action+":"+payload.getJSONArray("transfers");
        if(used.containsKey(id)){
            if(!identity.equals(used.get(id)))throw new IOException("drag_identity_reused");
            complete(payload,work);return;
        }
        if(used.size()>=256)throw new IOException("drag_limit");
        if((brand==1)!=action.equals("start"))throw new IOException("invalid_drag_direction");
        JSONArray ids=payload.getJSONArray("transfers");
        for(int i=0;i<ids.length();i++){
            Incoming item=incoming.get(ids.getString(i));
            if(item==null){main.postDelayed(()->apply(work),50);return;}
            if(!current(item)||item.selected)throw new IOException("stale_file");selected.add(item);
        }
        for(Incoming item:selected)item.selected=true;
        if(brand==1)startRemote(id,owned,selected);else for(Incoming item:selected)receiveDownload(item);
        used.put(id,identity);complete(payload,work);
    }catch(Exception e){for(Incoming item:selected)if(item.selected)revoke(item);log("control_failed "+e);fail(work,"native_unavailable");}}
    private void complete(JSONObject payload,ControlWork work)throws JSONException{
        JSONObject result=new JSONObject().put("stream_id",payload.getLong("stream_id")).put("drag_id",payload.getString("drag_id")).put("action",payload.getString("action")).put("accepted",true);
        work.done=true;controls.remove(work.key,work);work.completion.success(result);
    }
    private void fail(ControlWork work,String code){if(!work.done){work.done=true;controls.remove(work.key,work);work.completion.fail(code);}}
    private void startRemote(String id,ScreenCastSession.Scope scope,List<Incoming> selected)throws Exception{
        if(remote!=null)throw new IOException("drag_busy");View view=sink.get().dragView();if(view==null)throw new IOException("screen_closed");
        if(!Long.toString(scope.streamId()).equals(used.get("capture:"+id)))throw new IOException("drag_capture_unbound");
        if(!sink.get().dragPointerHeld(scope))throw new IOException("drag_pointer_released");
        Object handler=field(view.getClass(),"D4").get(view);List<String> types=new ArrayList<>();for(Incoming item:selected)types.add(item.prepared.metadata().offer().mime());
        ClipData clip=new ClipData("来自手机",types.toArray(String[]::new),new ClipData.Item(provider.stage(selected.get(0).prepared)));
        for(int i=1;i<selected.size();i++)clip.addItem(new ClipData.Item(provider.stage(selected.get(i).prepared)));
        remote=new RemoteGesture(id,scope,view,handler,List.copyOf(selected));
        View.DragShadowBuilder shadow=new View.DragShadowBuilder(view){
            public void onProvideShadowMetrics(android.graphics.Point size,android.graphics.Point touch){size.set(160,96);touch.set(80,48);}
            public void onDrawShadow(android.graphics.Canvas canvas){android.graphics.Paint paint=new android.graphics.Paint(3);paint.setColor(0xee285ec8);canvas.drawRoundRect(0,0,160,96,16,16,paint);paint.setColor(-1);paint.setTextSize(24);canvas.drawText("文件 ×"+selected.size(),16,58,paint);}
        };
        try{if(!view.startDragAndDrop(clip,shadow,handler,View.DRAG_FLAG_GLOBAL|View.DRAG_FLAG_GLOBAL_URI_READ))throw new IOException("native_drag_start_failed");}
        catch(Exception failed){remote=null;throw failed;}
        sink.get().releaseDragPointer();log("pad_native_drag_started count="+selected.size());
    }
    private CompletableFuture<Uri> receiveTemporary(Incoming item){
        if(!current(item)||!item.selected)return CompletableFuture.failedFuture(new IOException("stale_session"));
        synchronized(item){if(item.receiving)return item.local;item.receiving=true;}
        item.preparing=true;
        try{worker.execute(()->{try{
            if(!current(item))throw new IOException("file_cancelled");
            var lease=FileTransferReceiveDocuments.createTemporary(context,files.endpoint(),item.prepared.key(),item.prepared.metadata().offer(),k->current(item));item.lease=lease;
            if(!files.negotiation().approve(item.prepared,k->current(item),lease.accepted(),()->cleanup(item)))throw new IOException("receive_consent_failed");
            lease.committed().whenComplete((receipt,error)->{
                try{if(error!=null)throw new IOException(error);Uri uri=lease.claim(item.prepared.key(),item.prepared.metadata().offer());item.local.complete(uri);log("receive_verified transfer="+receipt.key().transfer()+" sha256="+receipt.sha256());
                    main.postDelayed(()->revoke(item),120000);
                }catch(Exception e){item.local.completeExceptionally(e);revoke(item);}
            });
        }catch(Exception e){item.local.completeExceptionally(e);revoke(item);}
        finally{item.preparing=false;if(item.revoked)revoke(item);}});}
        catch(RejectedExecutionException busy){item.preparing=false;item.local.completeExceptionally(busy);revoke(item);}
        return item.local;
    }
    private void receiveDownload(Incoming item){
        synchronized(item){if(item.receiving)return;item.receiving=true;}
        item.preparing=true;
        try{worker.execute(()->{try{
            if(!current(item))throw new IOException("stale_session");var offer=item.prepared.metadata().offer();
            ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,offer.name());values.put(MediaStore.Downloads.MIME_TYPE,offer.mime());
            values.put(MediaStore.Downloads.RELATIVE_PATH,"Download/YueJie");values.put(MediaStore.Downloads.IS_PENDING,1);
            final Uri target=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
            if(target==null)throw new IOException("destination_unavailable");item.downloadUri=target;
            var destination=FileTransferUris.newDocumentSink(context,target);
            var authorized=files.endpoint().authorizeTracked(item.prepared.key(),offer,new FileTransferEngine.Sink(){
                public void publishVerified(File staged,FileTransferEngine.Offer actual,FileTransferEngine.SessionGate gate,FileTransferEngine.Key key)throws IOException{
                    if(!current(item))throw new IOException("stale_session");destination.publishVerified(staged,actual,k->current(item)&&gate.current(k),key);
                    ContentValues ready=new ContentValues();ready.put(MediaStore.Downloads.IS_PENDING,0);
                    if(context.getContentResolver().update(target,ready,null,null)!=1)throw new IOException("destination_publish_failed");
                }
                public void rollback()throws IOException{destination.rollback();}
            });
            if(!files.negotiation().approve(item.prepared,k->current(item),authorized.thenApply(a->null),()->cleanup(item)))throw new IOException("receive_consent_failed");
            authorized.thenCompose(FileTransferEngine.Authorization::committed).whenComplete((receipt,error)->{
                if(error!=null){item.local.completeExceptionally(error);revoke(item);toast("接收文件失败");}
                else{item.downloadCommitted=true;item.local.complete(target);log("download_verified uri="+target+" size="+receipt.size()+" sha256="+receipt.sha256());toast("文件已保存到 Download/YueJie");
                    main.postDelayed(()->revoke(item),2000);
                }
            });
        }catch(Exception e){item.local.completeExceptionally(e);revoke(item);log("download_failed "+e);toast("接收文件失败");}
        finally{item.preparing=false;if(item.revoked)revoke(item);}});}
        catch(RejectedExecutionException busy){item.preparing=false;item.local.completeExceptionally(busy);revoke(item);toast("文件传输繁忙");}
    }
    /** A failed authorization still owns its newly inserted pending row. Revoke the
     * executor before deleting it, and retain failed cleanup for the maintenance retry. */
    private boolean cleanup(Incoming item){
        synchronized(item){
            if(item.disposed)return true;
            if(item.preparing)return false;
            if(item.cleanupWork!=null&&!item.cleanupWork.isDone())return false;
            if(item.cleanupWork!=null&&!item.cleanupWork.isCompletedExceptionally()){item.disposed=true;return true;}
            item.cleanupWork=files.endpoint().revoke(item.prepared.key()).thenRunAsync(()->{
                try{
                    if(item.lease!=null)item.lease.close();
                    if(item.downloadUri!=null&&!item.downloadCommitted)context.getContentResolver().delete(item.downloadUri,null,null);
                }catch(IOException|RuntimeException failure){throw new CompletionException(failure);}
            },worker);
            return false;
        }
    }
    private void revoke(Incoming item){item.revoked=true;item.local.completeExceptionally(new IOException("file_cancelled"));
        if(!item.grantsRevoked)try{if(provider!=null)provider.revoke(item.prepared);item.grantsRevoked=true;}
        catch(RuntimeException retry){log("drag_grant_release_pending");}
        files.negotiation().revoke(item.prepared.key());
        if(cleanup(item)&&item.grantsRevoked)incoming.remove(item.prepared.key().transfer(),item);
    }
    public void cancel(String session,String request){main.post(()->{
        ControlWork work=controls.get(session+":"+request);if(work!=null)fail(work,"file_cancelled");
    });}
    public void sessionChanged(){main.post(this::tick);}
    private Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,false,loader);}
    private static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void log(String text){ScreenCastLog.i("YueJieFiles",text);}
    private void toast(String text){main.post(()->Toast.makeText(context,text,Toast.LENGTH_SHORT).show());}
}
