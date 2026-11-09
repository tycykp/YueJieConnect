package io.github.yuejie.connect;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Actual shared-client binding. Preparation never allocates a file or grants native consent.
 * Receiver callbacks stage their own OEM handles; only a real local event may call approve(). */
public final class FileTransferBridge implements AutoCloseable {
    public static final Set<String> OPERATIONS = Set.of("file_prepare", "file_offer", "file_chunk", "file_commit", "file_cancel");
    public interface Registration extends AutoCloseable { @Override void close(); }
    public interface NativeReceiver {
        void prepared(FileTransferNegotiation.Prepared prepared, FileTransferEndpoint endpoint,
                      FileTransferNegotiation negotiation);
        default void revoked(FileTransferNegotiation.Prepared prepared) {}
        default void sessionChanged() {}
        default void close() {}
    }
    private static final class Outgoing {
        final FileTransferEngine.Key key;
        FileTransferNegotiation.Purpose purpose;
        String token;
        volatile String inFlight;
        FileTransferEngine.Offer offer;
        long step, deadline;
        volatile boolean approved, failed, committed, cancelRequested, cancelSent, releaseRequested, releasePending, released;
        CompletableFuture<Void> decision;
        CompletableFuture<Boolean> release;
        Outgoing(FileTransferEngine.Key key) { this.key = key; }
    }
    private final BridgeClient client;
    private final BooleanSupplier allowed;
    private final boolean sendOnly;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FileTransferEndpoint endpoint;
    private final FileTransferNegotiation negotiation;
    private final FileTransferClient fileClient;
    private final Map<FileTransferNegotiation.Purpose, NativeReceiver> receivers = new EnumMap<>(FileTransferNegotiation.Purpose.class);
    private final Map<FileTransferEngine.Key, NativeReceiver> receiveOwners = new HashMap<>();
    private final Map<FileTransferEngine.Key, Outgoing> outgoing = new HashMap<>();
    private final Map<FileTransferEngine.Key, FileTransferSource> sourceCleanup = new HashMap<>();
    private final Map<String, BridgeClient.Subscription> registrations = new HashMap<>();
    private final Set<FileTransferEngine.Key> incomingReleased = new HashSet<>(), incomingReleasing = new HashSet<>();
    private final Set<FileTransferEngine.Key> revokedNotified = new HashSet<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "yj-file-control"); thread.setDaemon(true); return thread;
    });
    private final ThreadPoolExecutor sendWorker = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), r -> {Thread thread=new Thread(r,"yj-file-send");thread.setDaemon(true);return thread;});
    private final BridgeClient.Subscription projection;
    private final CompletableFuture<Void> retired = new CompletableFuture<>();
    private String tableSession;
    private int allocating;
    private volatile boolean closed;
    private boolean endpointClosed;

    public FileTransferBridge(Context context, BridgeClient client, BooleanSupplier verifiedNativeContextAllowed) throws IOException {
        this(context, client, verifiedNativeContextAllowed, false);
    }

    static FileTransferBridge nfcSender(Context context, BridgeClient client) throws IOException {
        return new FileTransferBridge(context, client, () -> true, true);
    }

    private FileTransferBridge(Context context, BridgeClient client, BooleanSupplier allowed, boolean sendOnly) throws IOException {
        this.client = client; this.allowed = allowed; this.sendOnly = sendOnly;
        String pkg = context.getPackageName();
        if (sendOnly ? !"io.github.yuejie.connect".equals(pkg) : !("com.xiaomi.mirror".equals(pkg) || "com.oplus.linker".equals(pkg)))
            throw new SecurityException("invalid_executor_context");
        negotiation = new FileTransferNegotiation(this::current, SystemClock::elapsedRealtime);
        endpoint = sendOnly ? null : new FileTransferEndpoint(context, this::current);
        fileClient = new FileTransferClient(new FileTransferClient.Transport() {
            public boolean current(FileTransferEngine.Key key) { return outgoingCurrent(key); }
            public CompletableFuture<JSONObject> request(FileTransferEngine.Key key, String operation, JSONObject payload) {
                return requestData(key, operation, payload);
            }
        }, sendWorker);
        projection = client.addProjectionListener(ignored -> sessionChanged());
        timer.scheduleWithFixedDelay(this::maintain, 1, 1, TimeUnit.SECONDS);
    }
    public FileTransferEndpoint endpoint() { return endpoint; }
    public FileTransferNegotiation negotiation() { return negotiation; }
    public FileTransferClient fileClient() { return fileClient; }
    public CompletableFuture<Void> whenRetired() { return retired.copy(); }

    /** Main/native setup only. A registered receiver must have its actual OEM provider/sink ready. */
    public Registration registerReceiver(FileTransferNegotiation.Purpose purpose, NativeReceiver receiver) {
        synchronized (receivers) {
            if (closed || sendOnly || receivers.putIfAbsent(purpose, receiver) != null) throw new IllegalStateException("file_receiver_already_registered");
        }
        updateRegistration();
        return () -> {
            synchronized (receivers) { if (!receivers.remove(purpose, receiver)) return; }
            for (var prepared : negotiation.pending(purpose)) {
                NativeReceiver owner; synchronized(receiveOwners){owner=receiveOwners.get(prepared.key());}
                if(owner==receiver)revokePrepared(prepared);
            }
            main.post(receiver::close); updateRegistration();
        };
    }
    public boolean ready() {
        JSONObject p = client.projection();
        synchronized (registrations) { return !closed && contextAllowed() && (sendOnly ? client.hasLocalGrant("file_prepare") : registrations.size() == OPERATIONS.size())
                && p != null && p.optString("session_id").matches("[0-9a-f]{64}") && operations(p.optJSONArray("operations")); }
    }
    public boolean current(FileTransferEngine.Key key) {
        JSONObject p = client.projection();
        return ready() && p != null && key.peer().equals(p.optString("id")) && key.session().equals(p.optString("session_id"));
    }
    private boolean contextAllowed() { try { return allowed.getAsBoolean(); } catch (RuntimeException denied) {return false;} }
    private static boolean operations(JSONArray values) {
        if (values == null) return false;
        Set<String> found = new HashSet<>();
        for (int i=0;i<values.length();i++) found.add(values.optString(i));
        return found.containsAll(OPERATIONS);
    }
    private void updateRegistration() {
        boolean register = !closed && contextAllowed();
        synchronized (receivers) { register &= !receivers.isEmpty(); }
        for (String operation : OPERATIONS) register &= client.hasLocalGrant(operation);
        synchronized (registrations) {
            if (register && registrations.isEmpty()) {
                try { for (String operation : OPERATIONS) registrations.put(operation, client.registerExecutor(operation, executor)); }
                catch (RuntimeException failed) { for (var subscription : registrations.values()) subscription.close(); registrations.clear(); }
            } else if (!register && !registrations.isEmpty()) {
                var previous = new ArrayList<>(registrations.values()); registrations.clear();
                for (var subscription : previous) subscription.close();
            }
        }
    }

    /** Asynchronous public allocator. No UUID generated in an OEM process can bypass JNI slots. */
    public CompletableFuture<FileTransferEngine.Key> allocateKey() {
        CompletableFuture<FileTransferEngine.Key> result = new CompletableFuture<>();
        JSONObject p = client.projection();
        if (!ready() || p == null) return failed("stale_session");
        String peer=p.optString("id"), session=p.optString("session_id");
        synchronized (outgoing) {
            if (allocating + outgoing.values().stream().filter(o -> !o.released).count() >= 4) return failed("file_busy");
            if (tableSession != null && !tableSession.equals(session)) return failed("stale_session");
            if (outgoing.size() + allocating >= FileTransferNegotiation.SESSION_TRANSFERS) return failed("file_transfer_limit");
            allocating++;
        }
        client.allocateFile(session, response -> {
            FileTransferEngine.Key key = null;
            try {
                if (response == null || !"ok".equals(response.optString("status")) || !session.equals(response.optString("session_id")))
                    throw new IOException("file_allocation_failed");
                key = new FileTransferEngine.Key(peer, session, FileTransferProtocol.string(response,"transfer"));
                synchronized (outgoing) {
                    if (outgoing.containsKey(key)) throw new IOException("duplicate_transfer");
                    outgoing.put(key,new Outgoing(key)); if(tableSession==null)tableSession=session;
                }
                if (!current(key) || result.isCancelled()) { release(key); throw new IOException("stale_session"); }
                result.complete(key);
            } catch (Exception invalid) { result.completeExceptionally(new IOException(fixed(invalid.getMessage()))); }
            finally { synchronized(outgoing){allocating--;} }
        });
        return result;
    }
    public CompletableFuture<FileTransferEngine.Key> allocate(FileTransferNegotiation.Purpose purpose, String token) {
        return allocateKey().thenCompose(key -> {
            if (bindOutgoing(key,purpose,token)) return CompletableFuture.completedFuture(key);
            release(key); return failed("native_file_route_unbound");
        });
    }
    /** Allows a real native event to take a previously allocated key without provider/network I/O. */
    public boolean bindOutgoing(FileTransferEngine.Key key, FileTransferNegotiation.Purpose purpose, String token) {
        if (purpose == null || (sendOnly && purpose != FileTransferNegotiation.Purpose.NFC_TAP)
                || token == null || !token.matches("[0-9a-f]{32}") || !current(key)) return false;
        synchronized (outgoing) {
            Outgoing work=outgoing.get(key);
            if(work==null||work.released||work.releaseRequested||work.failed)return false;
            if(work.purpose!=null)return work.purpose==purpose&&token.equals(work.token);
            if(outgoing.values().stream().anyMatch(other -> token.equals(other.token)&&other.purpose==purpose))return false;
            work.purpose=purpose;work.token=token;return true;
        }
    }
    public CompletableFuture<Void> awaitReceiver(FileTransferEngine.Key key, FileTransferEngine.Offer offer) {
        Outgoing work;
        synchronized(outgoing) {
            work=outgoing.get(key);
            if(work==null||!outgoingCurrent(key)||work.purpose==null)return failed("native_file_route_unbound");
            if(work.offer!=null&&!work.offer.equals(offer))return failed("consent_metadata_mismatch");
            if(work.decision==null) {
                work.offer=offer;work.decision=new CompletableFuture<>();
                work.deadline=SystemClock.elapsedRealtime()+(work.purpose==FileTransferNegotiation.Purpose.CLIPBOARD?300_000:30_000);
                schedule(() -> pollPrepare(work),0);
            }
        }
        CompletableFuture<Void> observed=work.decision.copy();
        observed.whenComplete((v,error)->{if(observed.isCancelled())cancel(key);});
        return observed;
    }
    private void pollPrepare(Outgoing work) {
        if(work.decision.isDone())return;
        if(!outgoingCurrent(work.key)){work.decision.completeExceptionally(new IOException("stale_session"));return;}
        if(SystemClock.elapsedRealtime()>=work.deadline){work.decision.completeExceptionally(new IOException("request_timeout"));cancel(work.key);return;}
        try {
            JSONObject payload=FileTransferProtocol.offerPayload(work.key,work.offer)
                    .put("purpose",work.purpose.wireName()).put("native_owner_token",work.token);
            requestData(work.key,"file_prepare",payload).whenComplete((data,error)->{
                if(error!=null){work.decision.completeExceptionally(error);return;}
                if(Boolean.TRUE.equals(data.opt("approved"))){work.approved=true;work.decision.complete(null);}
                else schedule(()->pollPrepare(work),250);
            });
        } catch(JSONException failure){work.decision.completeExceptionally(new IOException("invalid_file_payload"));}
    }
    /** Takes source ownership, including approval failure. This method returns only file bytes ACK. */
    public CompletableFuture<FileTransferEngine.Receipt> send(FileTransferEngine.Key key, FileTransferSource source,
            FileTransferEngine.SessionGate actualNativeOwnerCurrent) {
        CompletableFuture<FileTransferEngine.Receipt> result=new CompletableFuture<>();
        var decision=awaitReceiver(key,source.offer());
        result.whenComplete((receipt,error)->{if(result.isCancelled()){decision.cancel(true);cancel(key);}});
        decision.whenComplete((ignored,error)->{
            boolean nativeCurrent;
            try{nativeCurrent=actualNativeOwnerCurrent.current(key);}catch(RuntimeException denied){nativeCurrent=false;}
            if(error!=null||result.isCancelled()||!nativeCurrent) {
                cleanupSource(key,source);
                result.completeExceptionally(new IOException("receive_consent_failed"));return;
            }
            var sent=fileClient.send(key,source,actualNativeOwnerCurrent);
            result.whenComplete((r,e)->{if(result.isCancelled())sent.cancel(true);});
            sent.whenComplete((receipt,failure)->{if(failure!=null)result.completeExceptionally(failure);else result.complete(receipt);});
        });
        return result;
    }
    private boolean outgoingCurrent(FileTransferEngine.Key key) {
        if(!current(key))return false;
        synchronized(outgoing){Outgoing work=outgoing.get(key);return work!=null&&!work.released&&!work.cancelRequested&&!work.failed;}
    }
    private CompletableFuture<JSONObject> requestData(FileTransferEngine.Key key,String operation,JSONObject payload) {
        CompletableFuture<JSONObject> result=new CompletableFuture<>();
        Outgoing work;String request;
        synchronized(outgoing) {
            work=outgoing.get(key);
            if(work==null||work.released||!current(key)||!OPERATIONS.contains(operation))return failed("stale_session");
            if(!operation.equals("file_cancel")&&(work.failed||work.cancelRequested))return failed("file_cancelled");
            if(!operation.equals("file_prepare")&&!operation.equals("file_cancel")&&!work.approved)return failed("receive_consent_required");
            if(work.inFlight!=null)return failed("file_busy");
            if(work.step==Long.MAX_VALUE)return failed("file_transfer_limit");
            request="file:"+key.transfer()+":"+work.step++;work.inFlight=request;
        }
        result.whenComplete((data,error)->{if(result.isCancelled())client.cancel(key.session(),request,ignored->{});});
        client.request(key.peer(),key.session(),operation,request,payload,response->{
            synchronized(outgoing){if(!request.equals(work.inFlight))return;work.inFlight=null;}
            try {
                if(!current(key))throw new IOException("stale_session");
                if(response==null||!"ok".equals(response.optString("status")))throw new IOException(fixed(response==null?null:response.optString("code")));
                if(!key.session().equals(response.optString("session_id"))||!request.equals(response.optString("request_id")))throw new IOException("invalid_file_result");
                JSONObject receipt=response.getJSONObject("result");
                if(!operation.equals(receipt.optString("operation")))throw new IOException("invalid_file_result");
                JSONObject data=receipt.getJSONObject("data");validateResult(work,operation,payload,data);
                if(operation.equals("file_commit"))work.committed=true;
                result.complete(new JSONObject(data.toString()));
            }catch(Exception failure){work.failed=true;result.completeExceptionally(new IOException(fixed(failure.getMessage())));}
            if(work.cancelRequested&&!work.committed)sendCancel(work);
            if(work.releaseRequested)releaseSlot(work);
        });
        return result;
    }
    private static void validateResult(Outgoing work,String operation,JSONObject payload,JSONObject data) throws Exception {
        if(!work.key.transfer().equals(FileTransferProtocol.string(data,"transfer")))throw new IOException("invalid_file_result");
        switch(operation){
            case "file_prepare" -> {FileTransferProtocol.fields(data,"transfer","prepared","approved");
                if(!Boolean.TRUE.equals(data.opt("prepared"))||!(data.opt("approved") instanceof Boolean))throw new IOException("invalid_file_result");}
            case "file_offer" -> {FileTransferProtocol.fields(data,"transfer","offset");
                if(work.offer==null||FileTransferProtocol.integer(data,"offset")>work.offer.size())throw new IOException("invalid_file_result");}
            case "file_chunk" -> {FileTransferProtocol.fields(data,"transfer","offset");
                long expected=FileTransferProtocol.integer(payload,"offset")+java.util.Base64.getDecoder().decode(payload.getString("data")).length;
                if(FileTransferProtocol.integer(data,"offset")!=expected)throw new IOException("invalid_file_result");}
            case "file_commit" -> {FileTransferProtocol.fields(data,"transfer","size","sha256","committed");
                if(work.offer==null||!Boolean.TRUE.equals(data.opt("committed"))||FileTransferProtocol.integer(data,"size")!=work.offer.size()
                        ||!work.offer.sha256().equals(FileTransferProtocol.string(data,"sha256")))throw new IOException("invalid_file_result");}
            case "file_cancel" -> {FileTransferProtocol.fields(data,"transfer","cancelled");if(!(data.opt("cancelled") instanceof Boolean))throw new IOException("invalid_file_result");}
            default -> throw new IOException("invalid_file_operation");
        }
    }

    public void cancel(FileTransferEngine.Key key) {
        Outgoing work;
        synchronized(outgoing){work=outgoing.get(key);if(work==null||work.released)return;work.cancelRequested=true;}
        if(work.decision!=null)work.decision.completeExceptionally(new IOException("file_cancelled"));
        if(work.inFlight!=null)client.cancel(key.session(),work.inFlight,ignored->{});else if(!work.committed)sendCancel(work);
    }
    private void sendCancel(Outgoing work) {
        synchronized(outgoing){if(work.cancelSent||work.inFlight!=null||work.released||!current(work.key))return;work.cancelSent=true;}
        try{requestData(work.key,"file_cancel",FileTransferProtocol.identity(work.key)).whenComplete((data,error)->{if(work.releaseRequested)releaseSlot(work);});}
        catch(JSONException ignored){ /* Fixed identity cannot fail JSON encoding. */ }
    }
    public CompletableFuture<Boolean> release(FileTransferEngine.Key key) {
        Outgoing work;
        synchronized(outgoing){work=outgoing.get(key);if(work==null)return CompletableFuture.completedFuture(false);
            if(work.release==null)work.release=new CompletableFuture<>();work.releaseRequested=true;}
        if(!work.committed)cancel(key);releaseSlot(work);return work.release.copy();
    }
    private void releaseSlot(Outgoing work) {
        synchronized(outgoing){if(work.released){if(work.release!=null)work.release.complete(true);return;}
            if(!work.releaseRequested||work.releasePending||work.inFlight!=null||sourceCleanup.containsKey(work.key))return;work.releasePending=true;}
        client.releaseFile(work.key.session(),work.key.transfer(),"outgoing",response->{
            synchronized(outgoing){work.releasePending=false;
                if(releasedReply(work.key,response)||endedSession(work.key,response)){work.released=true;if(work.release!=null)work.release.complete(true);}}
        });
    }
    private boolean endedSession(FileTransferEngine.Key key,JSONObject reply) {
        JSONObject p=client.projection();String code=reply==null?"":reply.optString("code");
        return p!=null&&!key.session().equals(p.optString("session_id"))
                || (p==null&&Set.of("stale_session","session_expired").contains(code));
    }
    private static boolean releasedReply(FileTransferEngine.Key key,JSONObject reply) {
        return reply!=null&&"ok".equals(reply.optString("status"))&&key.session().equals(reply.optString("session_id"))
                &&key.transfer().equals(reply.optString("transfer"))&&Boolean.TRUE.equals(reply.opt("released"));
    }
    private final BridgeClient.VendorExecutor executor=new BridgeClient.VendorExecutor(){
        public void execute(JSONObject task,BridgeClient.TaskCompletion completion){
            try {
                String operation=task.getString("operation");JSONObject payload=task.getJSONObject("payload");
                FileTransferEngine.Key key=new FileTransferEngine.Key(task.getString("projection_id"),task.getString("session_id"),FileTransferProtocol.string(payload,"transfer"));
                if(!current(key))throw new IOException("stale_session");
                if(SystemClock.elapsedRealtime()>=task.getLong("deadline_elapsed_ms"))throw new IOException("request_timeout");
                if(operation.equals("file_prepare")){
                    FileTransferProtocol.fields(payload,"transfer","purpose","native_owner_token","name","mime","size","sha256");
                    var metadata=new FileTransferNegotiation.Metadata(FileTransferNegotiation.Purpose.fromWire(FileTransferProtocol.string(payload,"purpose")),
                            FileTransferProtocol.string(payload,"native_owner_token"),FileTransferProtocol.offer(payload));
                    NativeReceiver receiver;synchronized(receivers){receiver=receivers.get(metadata.purpose());}
                    if(receiver==null)throw new IOException("native_file_receive_unavailable");
                    var state=negotiation.prepare(key,metadata);
                    if(state.firstSeen()){
                        synchronized(receiveOwners){receiveOwners.put(key,receiver);}
                        main.post(()->{
                        try {boolean registered;synchronized(receivers){registered=receivers.get(metadata.purpose())==receiver;}
                            if(!current(key)||!registered)throw new IllegalStateException("stale_session");receiver.prepared(state.prepared(),endpoint,negotiation);}
                        catch(RuntimeException failed){revokePrepared(state.prepared());}
                    });}
                    completion.success(new JSONObject().put("transfer",key.transfer()).put("prepared",true).put("approved",state.approved()));
                }else endpoint.execute(task,new FileTransferEndpoint.Completion(){
                    public void success(JSONObject data){
                        if(operation.equals("file_cancel")){
                            for(var purpose:FileTransferNegotiation.Purpose.values())for(var candidate:negotiation.pending(purpose))
                                if(candidate.key().equals(key)&&(!candidateApproved(candidate)||Boolean.TRUE.equals(data.opt("cancelled"))))revokePrepared(candidate);
                        }
                        completion.success(data);
                    }
                    public void fail(String code){completion.fail(code);}
                });
            }catch(Exception invalid){completion.fail(fixed(invalid.getMessage()));}
        }
        public void cancel(String session,String request){endpoint.cancel(session,request);}
        public void sessionChanged(){FileTransferBridge.this.sessionChanged();}
    };
    private boolean candidateApproved(FileTransferNegotiation.Prepared candidate){
        try{return negotiation.prepare(candidate.key(),candidate.metadata()).approved();}catch(IOException invalid){return false;}
    }
    private void revokePrepared(FileTransferNegotiation.Prepared prepared){
        negotiation.revoke(prepared.key());notifyRevoked(prepared);
    }
    private void notifyRevoked(FileTransferNegotiation.Prepared prepared){
        synchronized(revokedNotified){if(!revokedNotified.add(prepared.key()))return;}
        NativeReceiver receiver;synchronized(receiveOwners){receiver=receiveOwners.get(prepared.key());}
        if(receiver!=null)main.post(()->receiver.revoked(prepared));
    }
    private void cleanupSource(FileTransferEngine.Key key,FileTransferSource source){
        try{source.close();synchronized(outgoing){sourceCleanup.remove(key,source);}}
        catch(IOException retry){synchronized(outgoing){sourceCleanup.put(key,source);}}
    }
    public void sessionChanged(){
        JSONObject p=client.projection();String actual=p==null?null:p.optString("session_id");
        negotiation.sessionChanged(actual!=null&&actual.matches("[0-9a-f]{64}")?actual:null);
        if(endpoint!=null)endpoint.sessionChanged();
        synchronized(outgoing){for(Outgoing work:new ArrayList<>(outgoing.values()))if(!current(work.key)){cancel(work.key);release(work.key);}}
        synchronized(receivers){for(NativeReceiver receiver:receivers.values())main.post(receiver::sessionChanged);}
        maintain();
    }
    private void maintain(){
        try {
            main.post(this::updateRegistration);negotiation.maintain();
            Map<FileTransferEngine.Key,FileTransferSource> cleanup;
            synchronized(outgoing){cleanup=new HashMap<>(sourceCleanup);}
            for(var entry:cleanup.entrySet())cleanupSource(entry.getKey(),entry.getValue());
            synchronized(outgoing){for(Outgoing work:outgoing.values())if(work.releaseRequested)releaseSlot(work);
                JSONObject p=client.projection();String actual=p==null?null:p.optString("session_id");
                if(actual!=null&&actual.matches("[0-9a-f]{64}")&&tableSession!=null&&!tableSession.equals(actual)
                        &&allocating==0&&outgoing.values().stream().allMatch(o->o.released)){outgoing.clear();tableSession=actual;}}
            var released=negotiation.released();
            var retainedKeys=negotiation.retainedKeys();
            synchronized(receiveOwners){receiveOwners.keySet().removeIf(key->!retainedKeys.contains(key));}
            var retained=released.stream().map(FileTransferNegotiation.Prepared::key).collect(java.util.stream.Collectors.toSet());
            JSONObject p=client.projection();String actual=p==null?null:p.optString("session_id");
            if(actual!=null){synchronized(incomingReleased){incomingReleased.removeIf(k->!actual.equals(k.session())&&!retained.contains(k));}
                synchronized(revokedNotified){revokedNotified.removeIf(k->!actual.equals(k.session())&&!retained.contains(k));}}
            for(var prepared:released){
                notifyRevoked(prepared);FileTransferEngine.Key key=prepared.key();
                synchronized(incomingReleased){if(incomingReleased.contains(key)||!incomingReleasing.add(key))continue;}
                client.releaseFile(key.session(),key.transfer(),"incoming",response->{synchronized(incomingReleased){incomingReleasing.remove(key);if(releasedReply(key,response)||endedSession(key,response))incomingReleased.add(key);}});
            }
            if(closed&&negotiation.resourcesReleased()){
                if(!endpointClosed){endpointClosed=true;if(endpoint!=null)endpoint.close();sendWorker.shutdown();}
                synchronized(outgoing){if(allocating==0&&outgoing.values().stream().allMatch(o->o.released)&&sendWorker.isTerminated()){
                    timer.shutdown();retired.complete(null);}}
            }
        }catch(RuntimeException unavailable){android.util.Log.w("YueJieFiles","file_bridge_maintenance_pending");}
    }
    private void schedule(Runnable task,long delay){try{timer.schedule(task,delay,TimeUnit.MILLISECONDS);}catch(RuntimeException closed){ /* Close owns cancellation. */ }}
    private static <T> CompletableFuture<T> failed(String code){return CompletableFuture.failedFuture(new IOException(code));}
    private static String fixed(String code){
        return code!=null&&Set.of("stale_session","session_expired","permission_denied","request_timeout","file_busy","file_transfer_limit","file_transfer_released",
            "file_cancelled","receive_consent_required","receive_consent_failed","consent_metadata_mismatch","native_file_owner_reused","native_file_route_unbound",
            "native_file_receive_unavailable","file_allocation_failed","duplicate_transfer","invalid_file_payload","invalid_file_result","invalid_file_operation",
            "invalid_chunk","offset_mismatch","file_length_exceeded","file_incomplete","file_hash_mismatch","native_file_hash_mismatch","native_file_receive_failed",
            "destination_cleanup_failed","executor_unavailable","busy","service_unavailable").contains(code)?code:"file_transfer_failed";
    }
    @Override public void close(){
        if(closed)return;closed=true;projection.close();updateRegistration();
        synchronized(outgoing){for(Outgoing work:new ArrayList<>(outgoing.values()))release(work.key);}
        negotiation.close();synchronized(receivers){for(NativeReceiver receiver:receivers.values())main.post(receiver::close);}
        maintain();
    }
}
