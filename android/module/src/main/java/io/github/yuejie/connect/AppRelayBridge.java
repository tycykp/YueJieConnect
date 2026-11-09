package io.github.yuejie.connect;

import android.content.Context;
import android.os.SystemClock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Concrete v3 glue. Shared owner must open app_relay and bind a real native peer before install. */
final class AppRelayBridge implements AutoCloseable {
    interface DocumentsFactory { AppRelayEndpoint.Documents forSession(String peer, String session); }
    interface DirectoryFactory { AppRelayDirectory.Consumer forSession(String peer, String session) throws Exception; }
    interface SourceFactory { AppRelaySync.Native forSession(String peer, String session); }
    private static final class Scope {
        final String peer, session;
        volatile boolean active = true;
        AppRelayReceiver receiver;
        AppRelayEndpoint.Documents documents;
        AppRelayDirectory directory;
        AppRelaySync sync;
        final java.util.Set<String> observed = new java.util.HashSet<>();
        Scope(String peer, String session) { this.peer = peer; this.session = session; }
    }
    private static final class Work {
        final Scope scope;
        final String request;
        final long deadline;
        volatile boolean cancelled;
        Work(Scope scope, String request, long deadline) { this.scope = scope; this.request = request; this.deadline = deadline; }
    }
    private final BridgeClient client;
    private final Context context;
    private final ClassLoader loader;
    private final int brand;
    private final BooleanSupplier allowed;
    private BooleanSupplier registrationReady;
    private final DocumentsFactory documents;
    private final DirectoryFactory directories;
    private final SourceFactory sources;
    private final java.util.concurrent.atomic.AtomicLong catalogRevision = new java.util.concurrent.atomic.AtomicLong();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), r -> { Thread t = new Thread(r, "yj-app-relay"); t.setDaemon(true); return t; });
    private final Map<String, Work> work = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JSONObject>> outbound = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService cleanupTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "yj-relay-retired-cleanup"); t.setDaemon(true); return t;
    });
    private final AppRelaySender sender;
    private final AppRelaySyncClient syncClient;
    private final CompletableFuture<Void> retired = new CompletableFuture<>();
    private volatile Scope scope;
    private volatile Work executing;
    private volatile boolean closed;
    private volatile String revokedSession = "";
    private BridgeClient.Subscription executorSubscription, projectionSubscription;

    AppRelayBridge(BridgeClient client, ClassLoader loader, int brand, BooleanSupplier allowed, DocumentsFactory documents) {
        this(client, loader, brand, allowed, documents, null);
    }
    AppRelayBridge(BridgeClient client, ClassLoader loader, int brand, BooleanSupplier allowed, DocumentsFactory documents, DirectoryFactory directories) {
        this(client, loader, brand, allowed, documents, directories, null);
    }
    AppRelayBridge(BridgeClient client, ClassLoader loader, int brand, BooleanSupplier allowed, DocumentsFactory documents,
                   DirectoryFactory directories, SourceFactory sources) {
        if (brand != 1 && brand != 2) throw new IllegalArgumentException("invalid_brand");
        this.client = client; this.context = client.context(); this.loader = loader; this.brand = brand;
        this.allowed = allowed; this.documents = documents; this.sender = new AppRelaySender(transport);
        this.registrationReady = allowed;
        this.syncClient = new AppRelaySyncClient(transport);
        this.directories = directories;
        this.sources = sources;
        String pkg = brand == 1 ? "com.xiaomi.mirror" : "com.oplus.linker";
        if (context == null || !pkg.equals(context.getPackageName())) throw new SecurityException("invalid_executor_context");
        cleanupTimer.scheduleWithFixedDelay(this::cleanup, 5, 5, TimeUnit.SECONDS);
    }
    void install() throws ReflectiveOperationException {
        if (closed || executorSubscription != null || projectionSubscription != null) throw new IllegalStateException("relay_lifecycle");
        // Symbol resolution must succeed before the public capability is registered.
        try {
            endpoint(null).close();
            if (canRegister()) executorSubscription = client.registerExecutor("app_relay", executor);
            projectionSubscription = client.addProjectionListener(ignored -> nativeStateChanged());
            nativeStateChanged();
        } catch (ReflectiveOperationException | RuntimeException failure) { close(); throw failure; }
    }
    AppRelaySender sender() { return sender; }
    void registrationReady(BooleanSupplier ready) {
        if(projectionSubscription!=null||executorSubscription!=null)throw new IllegalStateException("relay_already_installed");
        registrationReady=java.util.Objects.requireNonNull(ready);
    }
    private boolean canRegister(){JSONObject p=client.projection();return !closed&&registrationReady.getAsBoolean()
        &&(p==null||!revokedSession.equals(p.optString("session_id")));}
    boolean authorized() { return current(scope); }
    // Close source click hooks only after this resolves, so a queued old launcher click remains
    // guarded until its native directory entry was withdrawn or the slot was transferred.
    CompletableFuture<Void> whenRetired() { return retired.copy(); }
    // Local receive-decision bridge only. Returns the exact document registry owned by this scope,
    // including before the first prepare RPC, so early native approvals cannot create an orphan registry.
    CompletableFuture<AppRelayEndpoint.Documents> documentsForSession(String peer, String session) {
        CompletableFuture<AppRelayEndpoint.Documents> result = new CompletableFuture<>();
        try { worker.execute(() -> {
            Scope active = scope;
            try {
                if (!current(active) || !peer.equals(active.peer) || !session.equals(active.session)) throw new IllegalStateException("stale_session");
                synchronized (active) {
                    if (!current(active)) throw new IllegalStateException("stale_session");
                    if (documents == null) throw new IllegalStateException("file_transfer_required");
                    if (active.documents == null) active.documents = documents.forSession(peer, session);
                    if (active.documents == null) throw new IllegalStateException("file_transfer_required");
                    result.complete(active.documents);
                }
            } catch (Exception error) { result.completeExceptionally(error); }
        }); } catch (java.util.concurrent.RejectedExecutionException unavailable) { result.completeExceptionally(unavailable); }
        return result;
    }
    // Call on the OEM selected-peer/channel/grant callback, on the bridge main thread. Retain this
    // object across native readiness changes: rebuilding it in the same session would lose no-replay state.
    synchronized void nativeStateChanged() {
        if (closed || projectionSubscription == null) return;
        if (!canRegister()) {
            // Preparing local OEM hooks before a DATA channel opens is not revocation. refresh
            // retires (and tombstones) an actually active scope, never a not-yet-used session.
            BridgeClient.Subscription registration = executorSubscription; executorSubscription = null;
            refresh();
            if (registration != null) registration.close();
        } else if (executorSubscription == null) {
            JSONObject projection = client.projection();
            if (projection != null && !revokedSession.equals(projection.optString("session_id"))
                    && projection.optString("session_id").matches("[0-9a-f]{64}")) {
                executorSubscription = client.registerExecutor("app_relay", executor);
                refresh();
            }
        } else refresh();
    }
    CompletableFuture<JSONObject> publishCatalog(AppRelayPeer peer, AppRelayCatalog.Update update) {
        try {
            Scope active = scope;
            if (!current(active) || !active.peer.equals(peer.projectionId()) || !active.session.equals(peer.sessionId()))
                throw new IllegalStateException("stale_session");
            synchronized (active) { if (sources != null) sync(active).observe(update); }
            String request = "relay-catalog-" + UUID.randomUUID();
            long revision = catalogRevision.incrementAndGet();
            JSONObject catalog = AppRelayCatalogCodec.encode(revision, update);
            return transport.request(peer.projectionId(), peer.sessionId(), new JSONObject().put("action", "catalog")
                    .put("request_id", request).put("catalog", catalog)).thenApply(reply -> {
                if (reply.optInt("version") != 1 || !request.equals(reply.optString("request_id"))
                        || !"native_catalog_consumed".equals(reply.optString("code")) || reply.optLong("revision", -1) != revision
                        || !Boolean.TRUE.equals(reply.opt("applied"))) throw new IllegalStateException("invalid_remote_receipt");
                return reply;
            });
        } catch (Exception invalid) { return CompletableFuture.failedFuture(invalid); }
    }
    CompletableFuture<AppRelayTasks.Result> requestSync(AppRelayPeer peer, String sourcePackage) {
        return requestSync(peer, sourcePackage, "");
    }
    CompletableFuture<AppRelayTasks.Result> requestSync(AppRelayPeer peer, String sourcePackage, String nativeOwnerToken) {
        return syncClient.request(peer.projectionId(), peer.sessionId(), sourcePackage, nativeOwnerToken);
    }
    private AppRelaySync sync(Scope value) {
        if (sources == null) throw new IllegalStateException("native_source_unavailable");
        if (value.sync == null) value.sync = new AppRelaySync(sources.forSession(value.peer, value.session), () -> current(value), SystemClock::elapsedRealtime);
        return value.sync;
    }
    private AppRelayEndpoint endpoint(AppRelayEndpoint.Documents files) throws ReflectiveOperationException {
        return brand == 1 ? new XiaomiRelayEndpoint(context, loader, files) : new OplusRelayEndpoint(context, loader, files);
    }
    private boolean current(String peer, String session) {
        JSONObject projection = client.projection();
        if (closed || executorSubscription == null || session.equals(revokedSession) || !allowed.getAsBoolean() || projection == null || !peer.equals(projection.optString("id"))
                || !session.matches("[0-9a-f]{64}") || !session.equals(projection.optString("session_id"))) return false;
        JSONArray operations = projection.optJSONArray("operations");
        if (operations != null) for (int i = 0; i < operations.length(); i++) if ("app_relay".equals(operations.optString(i))) return true;
        return false;
    }
    private boolean current(Scope value) { return value != null && value.active && current(value.peer, value.session); }
    private synchronized void refresh() {
        JSONObject projection = client.projection();
        Scope old = scope;
        if (current(old)) return; // Battery/profile refreshes must not reset relay deduplication.
        scope = null;
        if (old != null) {
            old.active = false;
            revokedSession = old.session; // Regrant requires a fresh authenticated session, preserving no-replay semantics.
            // Close on the worker, never wait for PM/native ability queries on the main thread.
            enqueueCleanup(old);
        }
        if (projection != null && current(projection.optString("id"), projection.optString("session_id")))
            scope = new Scope(projection.optString("id"), projection.optString("session_id"));
        for (CompletableFuture<JSONObject> future : outbound.values())
            future.completeExceptionally(new IllegalStateException("stale_session"));
    }
    private void enqueueCleanup(Scope old) {
        // There is at most one active scope. If the bounded dispatch queue is full, cleanup is
        // retried by every queued worker before it can execute another task.
        retiring.put(old, Boolean.TRUE);
        try { worker.execute(this::cleanup); } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    private final Map<Scope, Boolean> retiring = new ConcurrentHashMap<>();
    private void cleanup() {
        Scope active = scope;
        if (active != null) synchronized (active) { if (active.sync != null) active.sync.expire(); }
        for (Scope value : retiring.keySet()) {
            synchronized (value) {
            if (!retiring.containsKey(value)) continue;
            try {
                if (value.sync != null) value.sync.close();
                if (value.receiver != null) value.receiver.close();
                if (value.directory != null) value.directory.close();
                if (value.documents instanceof AutoCloseable owned) owned.close();
                retiring.remove(value);
            } catch (Exception failure) { android.util.Log.w("YueJieRelay", "session_cleanup_failed"); }
            }
        }
        if (closed && retiring.isEmpty()) { cleanupTimer.shutdown(); retired.complete(null); }
    }
    private final AppRelaySender.Transport transport = new AppRelaySender.Transport() {
        @Override public boolean current(String peer, String session) { return AppRelayBridge.this.current(peer, session); }
        @Override public CompletableFuture<JSONObject> request(String peer, String session, JSONObject payload) {
            if (!current(peer, session)) return CompletableFuture.failedFuture(new IllegalStateException("stale_session"));
            String request = "relay-" + UUID.randomUUID();
            CompletableFuture<JSONObject> future = new CompletableFuture<>();
            synchronized (outbound) {
                if (outbound.size() >= 32) return CompletableFuture.failedFuture(new IllegalStateException("relay_busy"));
                outbound.put(request, future);
            }
            future.orTimeout(15, TimeUnit.SECONDS).whenComplete((value, error) -> {
                outbound.remove(request, future);
                if (error != null) {
                    // This is the outer wire ID. Cancelling a source sync must reach its native
                    // producer as well as retiring the local observer. No cancel ACK is a result.
                    try { client.cancel(session, request, ignored -> {}); } catch (RuntimeException ignored) { }
                }
            });
            try { client.request(peer, session, "app_relay", request, payload, response -> {
                JSONObject result = response == null ? null : response.optJSONObject("result");
                JSONObject data = result == null ? null : result.optJSONObject("data");
                if (current(peer, session) && response != null && "ok".equals(response.optString("status"))
                        && request.equals(response.optString("request_id")) && session.equals(response.optString("session_id"))
                        && result != null && "app_relay".equals(result.optString("operation")) && data != null) future.complete(data);
                else future.completeExceptionally(new IllegalStateException("invalid_remote_receipt"));
            }); } catch (RuntimeException failure) { future.completeExceptionally(failure); }
            if ("sync".equals(payload.optString("action"))) AppRelayEvents.emit(AppRelayEvents.Event.SYNC_SUBMITTED,
                    payload.optString("request_id"), AppRelayEvents.Outcome.PENDING);
            return future;
        }
    };
    private final BridgeClient.VendorExecutor executor = new BridgeClient.VendorExecutor() {
        @Override public void execute(JSONObject task, BridgeClient.TaskCompletion completion) {
            final Work item; final JSONObject payload; final String key;
            try {
                refresh();
                Scope active = scope;
                if (!current(active) || !"app_relay".equals(task.getString("operation"))
                        || !active.peer.equals(task.getString("projection_id")) || !active.session.equals(task.getString("session_id")))
                    throw new IllegalStateException("stale_session");
                payload = new JSONObject(task.getJSONObject("payload").toString());
                AppRelayCodec.validateAction(payload);
                item = new Work(active, payload.getString("request_id"), task.getLong("deadline_elapsed_ms"));
                key = active.session + ":" + task.getString("request_id");
                if (work.putIfAbsent(key, item) != null) throw new IllegalStateException("duplicate_relay_request");
            } catch (Exception invalid) { completion.fail(AppRelayTasks.code(invalid)); return; }
            try { worker.execute(() -> {
                cleanup();
                synchronized (item.scope) {
                executing = item;
                try {
                    if (item.cancelled) throw new IllegalStateException("cancelled");
                    if (SystemClock.elapsedRealtime() >= item.deadline) throw new IllegalStateException("request_timeout");
                    if (!current(item.scope)) throw new IllegalStateException("stale_session");
                    String action = payload.getString("action");
                    if ("sync".equals(action) || (("status".equals(action) || "cancel".equals(action))
                            && item.scope.sync != null && item.scope.sync.contains(item.request))) {
                        AppRelaySync registry = sync(item.scope);
                        if ("sync".equals(action)) {
                            boolean fresh = !registry.contains(item.request);
                            registry.request(item.request, payload.getString("source_package"), payload.optString("native_owner_token", ""));
                            if (fresh) AppRelayEvents.emit(AppRelayEvents.Event.SOURCE_REQUESTED, item.request, AppRelayEvents.Outcome.PENDING);
                        }
                        if ("cancel".equals(action) || item.cancelled) registry.cancel(item.request);
                        completion.success(AppRelayCodec.encode(registry.status(item.request)));
                        return;
                    }
                    if ("cancel".equals(action) && sources != null) sync(item.scope).cancel(item.request);
                    if ("catalog".equals(payload.getString("action"))) {
                        if (directories == null) throw new IllegalStateException("native_directory_unavailable");
                        if (item.scope.directory == null) item.scope.directory = new AppRelayDirectory(
                                directories.forSession(item.scope.peer, item.scope.session), () -> current(item.scope));
                        AppRelayCatalogCodec.Delivery delivery = AppRelayCatalogCodec.decode(payload.getJSONObject("catalog"));
                        boolean replayed = item.scope.directory.apply(delivery.revision(), delivery.update());
                        completion.success(new JSONObject().put("version", 1).put("request_id", item.request)
                                .put("revision", delivery.revision()).put("applied", true).put("replayed", replayed).put("code", "native_catalog_consumed"));
                        return;
                    }
                    if (item.scope.receiver == null) {
                        if (item.scope.documents == null && documents != null)
                            item.scope.documents = documents.forSession(item.scope.peer, item.scope.session);
                        AppRelayEndpoint endpoint = endpoint(item.scope.documents);
                        endpoint.dispatchAllowed = () -> current(item.scope) && executing != null && !executing.cancelled
                                && SystemClock.elapsedRealtime() < executing.deadline;
                        item.scope.receiver = new AppRelayReceiver(endpoint, () -> current(item.scope));
                    }
                    JSONObject result = item.scope.receiver.execute(payload);
                    if ("COMPLETED".equals(result.optString("state")) && "foreground_observed".equals(result.optString("code"))
                            && item.scope.observed.add(item.request))
                        AppRelayEvents.emit(AppRelayEvents.Event.TARGET_FOREGROUND, item.request, AppRelayEvents.Outcome.SUCCEEDED);
                    if (item.cancelled && "PREPARED".equals(result.optString("state"))) {
                        item.scope.receiver.execute(new JSONObject().put("action", "cancel").put("request_id", item.request));
                        completion.fail("cancelled");
                    } else if (item.cancelled && "FAILED".equals(result.optString("state"))
                            && "execution_revoked".equals(result.optString("code"))) {
                        // Endpoint rejected immediately before startActivity; no dispatch occurred.
                        completion.fail("cancelled");
                    } else if (!current(item.scope)) completion.fail("stale_session");
                    else completion.success(result); // LAUNCHING/FAILED remain explicit business states.
                } catch (Exception error) { completion.fail(AppRelayTasks.code(error)); }
                finally { executing = null; work.remove(key, item); cleanup(); }
                }
            }); } catch (java.util.concurrent.RejectedExecutionException busy) { work.remove(key, item); completion.fail("relay_busy"); }
        }
        @Override public void cancel(String session, String request) {
            Work item = work.get(session + ":" + request);
            if (item != null) {
                item.cancelled = true;
                synchronized (item.scope) { if (item.scope.sync != null && item.scope.sync.contains(item.request)) item.scope.sync.cancel(item.request); }
            }
        }
        @Override public void sessionChanged() { refresh(); }
    };
    @Override public synchronized void close() {
        if (closed) return;
        sender.close(); syncClient.close(); closed = true; refresh();
        if (projectionSubscription != null) projectionSubscription.close();
        if (executorSubscription != null) executorSubscription.close();
        worker.shutdown(); // Drain bounded work so every pending completion gets a real failure.
        if (retiring.isEmpty()) { cleanupTimer.shutdown(); retired.complete(null); }
    }
}
