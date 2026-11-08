package io.github.qiangjian.connect;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import io.github.libxposed.api.XposedModule;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native copy -> authenticated file_prepare -> actual native paste/grant -> bytes -> OEM readback ACK.
 * FileTransferBridge owns wire sequencing/negotiation; each accepted lease has exactly one URI owner. */
final class ClipboardUriBridge implements AutoCloseable {
    record Receipt(String peer, String session, String request, String transfer, long size, String sha256) {}
    private static final class Incoming {
        final FileTransferNegotiation.Prepared prepared;
        final ClipboardUriPasteHooks.Prepared local;
        final CompletableFuture<ClipboardUriPasteHooks.Received> receiving = new CompletableFuture<>();
        final CompletableFuture<ClipboardUriReceiver.Result> applied = new CompletableFuture<>();
        volatile boolean revoked;
        volatile FileTransferReceiveLease<Uri> lease;
        volatile boolean attached;
        volatile boolean preparing;
        Incoming(FileTransferNegotiation.Prepared prepared) {
            this.prepared = prepared;
            local = new ClipboardUriPasteHooks.Prepared(prepared.key(), prepared.metadata().offer(), prepared.metadata().nativeOwnerToken());
        }
    }
    private static final class Outgoing {
        final ClipboardUriSource.Event event;
        final CompletableFuture<Receipt> result = new CompletableFuture<>();
        volatile FileTransferEngine.Key key;
        volatile ClipboardUriTransfer snapshot;
        volatile CompletableFuture<?> pending;
        volatile boolean cancelled;
        volatile boolean allocating = true, preparing;
        final AtomicBoolean cleaning = new AtomicBoolean();
        boolean applying;
        Outgoing(ClipboardUriSource.Event event) { this.event = event; }
    }
    private final XposedModule module;
    private final Context context;
    private final BridgeClient client;
    private final FileTransferBridge files;
    private final BooleanSupplier allowed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ClipboardNativeContent endpoint;
    private final ClipboardEchoGuard echoes = new ClipboardEchoGuard();
    private final ClipboardUriRoutes deliveries;
    private final ClipboardUriPasteHooks paste;
    private final ClipboardUriSource source;
    private final OplusClipboardEndpoint oplus;
    private final XiaomiClipboardEndpoint xiaomi;
    private final Map<FileTransferEngine.Key, Incoming> incoming = new ConcurrentHashMap<>();
    private final Set<Outgoing> owned = ConcurrentHashMap.newKeySet();
    private final Map<String, FileTransferEngine.Key> wireRequests = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            task -> { Thread thread = new Thread(task, "qj-clipboard-uri-bridge"); thread.setDaemon(true); return thread; });
    private BridgeClient.Subscription subscription, executor;
    private FileTransferBridge.Registration registration;
    private ClipboardBridge.Probe uriProbe;
    private volatile boolean closed;
    private volatile Outgoing outgoing;
    private volatile CompletableFuture<Receipt> lastTransfer;
    private final Runnable refresh = this::update;
    private final Runnable finishClose = this::finishClose;

    ClipboardUriBridge(XposedModule module, Context context, ClassLoader loader, BridgeClient client,
            BooleanSupplier verifiedNativeContextAllowed, int brand, FileTransferBridge files) throws ReflectiveOperationException {
        mainThread();
        this.module = module; this.context = context; this.client = client; this.allowed = verifiedNativeContextAllowed; this.files = files;
        oplus = brand == 2 ? new OplusClipboardEndpoint(loader) : null;
        xiaomi = brand == 1 ? new XiaomiClipboardEndpoint(loader) : null;
        endpoint = new ClipboardNativeContent(module, context, loader, brand);
        deliveries = new ClipboardUriRoutes(endpoint, echoes, this::ready, client::projection);
        paste = new ClipboardUriPasteHooks(module, context, loader, brand, endpoint, echoes, new ClipboardUriPasteHooks.Routes() {
            @Override public boolean current(ClipboardUriPasteHooks.Prepared prepared) { return currentIncoming(incoming.get(prepared.key()), prepared); }
            @Override public CompletableFuture<ClipboardUriPasteHooks.Received> receive(ClipboardUriPasteHooks.Prepared prepared, ClipboardUriPasteHooks.Caller caller) {
                Incoming item = incoming.get(prepared.key());
                if (!currentIncoming(item, prepared)) return CompletableFuture.failedFuture(new IOException("stale_session"));
                main.post(() -> receiveSelected(item, caller));
                return item.receiving.copy();
            }
            @Override public void revoke(ClipboardUriPasteHooks.Prepared prepared) { Incoming item = incoming.get(prepared.key()); if (item != null) revokeIncoming(item); }
            @Override public void delivered(ClipboardUriPasteHooks.Prepared prepared, ClipboardUriPasteHooks.Caller caller) {
                main.post(() -> { ClipboardBridge.Probe probe = probe();
                    if (probe != null) probe.uriConsumed(prepared.key().session(), prepared.offer().name()); });
            }
        });
        source = new ClipboardUriSource(context, echoes, this::ready, client::projection, new ClipboardUriSource.Sink() {
            @Override public void copied(ClipboardUriSource.Event event) { send(event); }
            @Override public void invalidated() {
                cancelOutgoing();
                for (Incoming item : incoming.values()) revokeIncoming(item);
            }
        });
        try {
            paste.install();
            if (brand == 2) installUriProbe(loader);
            registration = files.registerReceiver(FileTransferNegotiation.Purpose.CLIPBOARD, new FileTransferBridge.NativeReceiver() {
                @Override public void prepared(FileTransferNegotiation.Prepared prepared, FileTransferEndpoint ignored, FileTransferNegotiation negotiation) {
                    if (closed || prepared.metadata().purpose() != FileTransferNegotiation.Purpose.CLIPBOARD || !ready()) return;
                    ClipboardBridge.Probe probe = probe();
                    if (probe != null && !probe.permitUriInbound(prepared.metadata().offer().name(), prepared.metadata().offer().size(), prepared.metadata().offer().sha256())) {
                        negotiation.revoke(prepared.key()); return;
                    }
                    Incoming item = new Incoming(prepared);
                    if (incoming.putIfAbsent(prepared.key(), item) != null) return;
                    for (Incoming prior : incoming.values()) if (prior != item) revokeIncoming(prior);
                    try {
                        if (!paste.stage(item.local)) revokeIncoming(item);
                        else if (probe != null) probe.uriStaged(prepared.key().session(), prepared.metadata().offer().name(), context.getSystemService(android.content.ClipboardManager.class).getPrimaryClip());
                    }
                    catch (ReflectiveOperationException | RuntimeException failed) { revokeIncoming(item); }
                }
                @Override public void revoked(FileTransferNegotiation.Prepared prepared) {
                    Incoming item = incoming.get(prepared.key()); if (item != null && item.prepared == prepared) revokeIncoming(item);
                }
                @Override public void sessionChanged() { ClipboardUriBridge.this.sessionChanged(); }
            });
            subscription = client.addProjectionListener(ignored -> main.post(this::sessionChanged));
            main.post(refresh);
        } catch (ReflectiveOperationException | RuntimeException failure) { close(); throw failure; }
    }
    private ClipboardBridge.Probe probe() { return uriProbe != null ? uriProbe : ClipboardBridge.activeProbe(); }
    private void installUriProbe(ClassLoader loader) {
        try {
            uriProbe = (ClipboardBridge.Probe) Class.forName("io.github.qiangjian.connect.ClipboardNativeProbe", false,
                    ClipboardUriBridge.class.getClassLoader()).getDeclaredMethod("installUri", Context.class, XposedModule.class,
                    ClassLoader.class, BridgeClient.class, int.class).invoke(null, context, module, loader, client, 2);
        } catch (ClassNotFoundException releaseBuild) { /* No debug receiver in release builds. */ }
        catch (ReflectiveOperationException | RuntimeException unavailable) { module.log(android.util.Log.WARN, "QiangJian", "clipboard_uri_probe_unavailable"); }
    }
    private boolean nativeAvailable() {
        return !closed && allowed.getAsBoolean() && (oplus == null || oplus.enabled(context)) && (xiaomi == null || xiaomi.available());
    }
    private boolean ready() {
        if (!nativeAvailable() || !files.ready()) return false;
        JSONObject p = client.projection(); JSONArray operations = p == null ? null : p.optJSONArray("operations");
        if (operations != null) for (int i = 0; i < operations.length(); i++) if ("clipboard_uri_write".equals(operations.optString(i))) return true;
        return false;
    }
    private void update() {
        if (closed) return;
        boolean available = nativeAvailable() && files.ready();
        if (available && executor == null) executor = client.registerExecutor("clipboard_uri_write", uriExecutor);
        else if (!available && executor != null) { executor.close(); executor = null; }
        source.update(); paste.sessionChanged(); deliveries.sessionChanged();
        ClipboardBridge.Probe probe = probe();
        if (probe != null) probe.attachUriTransfers(this::observedTransfer);
        incoming.entrySet().removeIf(entry -> entry.getValue().revoked && !entry.getValue().preparing
                && (entry.getValue().lease == null || entry.getValue().lease.disposed()));
        main.postDelayed(refresh, 1000);
    }
    private boolean currentIncoming(Incoming item, ClipboardUriPasteHooks.Prepared prepared) {
        return item != null && item.local.equals(prepared) && !item.revoked && ready() && files.current(prepared.key());
    }
    private void receiveSelected(Incoming item, ClipboardUriPasteHooks.Caller caller) {
        if (item.lease != null || item.receiving.isDone()) return;
        if (!currentIncoming(item, item.local) || !paste.permitted(item.local, caller)) { revokeIncoming(item); return; }
        item.preparing = true;
        if (!executeWorker(() -> {
            FileTransferReceiveLease<Uri> lease;
            FileTransferEngine.SessionGate decision = key -> key.equals(item.prepared.key()) && currentIncoming(item, item.local) && paste.permitted(item.local, caller);
            try {
                lease = FileTransferReceiveDocuments.createTemporary(context, files.endpoint(), item.prepared.key(), item.prepared.metadata().offer(), decision);
                item.lease = lease;
            } catch (IOException | RuntimeException failure) { item.preparing = false; revokeIncoming(item); return; }
            item.preparing = false;
            lease.accepted().whenComplete((ignored, failure) -> main.post(() -> {
                if (failure != null || !decision.current(item.prepared.key()) || !deliveries.attach(lease)) { revokeIncoming(item); disposeUnattached(item); return; }
                item.attached = true;
                boolean decided = files.negotiation().approve(item.prepared, decision, lease.accepted(), () -> {
                    revokeIncoming(item);
                    return lease.disposed();
                });
                if (!decided) { revokeIncoming(item); return; }
                item.receiving.complete(new ClipboardUriPasteHooks.Received(lease, item.applied.copy()));
                lease.committed().whenComplete((receipt, error) -> main.post(() -> {
                    if (error != null || !currentIncoming(item, item.local)) { revokeIncoming(item); return; }
                    deliveries.execute(item.prepared.key().peer(), item.prepared.key().session(), item.local.request(),
                            new ClipboardUriCodec.Request(item.prepared.key().transfer(), item.prepared.metadata().offer()), SystemClock.elapsedRealtime() + 30_000)
                            .whenComplete((result, failed) -> {
                                if (failed == null) {
                                    if (item.applied.complete(result) && result != null && result.applied()) {
                                        ClipboardBridge.Probe probe = probe();
                                        if (probe != null) probe.uriApplied(item.prepared.key().session(), item.prepared.metadata().offer().name(), context.getSystemService(android.content.ClipboardManager.class).getPrimaryClip());
                                    }
                                } else item.applied.completeExceptionally(failed);
                            });
                }));
            }));
        })) { item.preparing = false; revokeIncoming(item); }
    }
    private void revokeIncoming(Incoming item) {
        if (item.revoked) return;
        item.revoked = true;
        item.receiving.completeExceptionally(new IOException("clipboard_uri_paste_unavailable"));
        item.applied.completeExceptionally(new IOException("clipboard_uri_paste_unavailable"));
        main.post(() -> {
            files.negotiation().revoke(item.prepared.key());
            if (item.attached) deliveries.revoke(item.prepared.key());
            else disposeUnattached(item);
        });
    }
    private void disposeUnattached(Incoming item) {
        if (item.attached || item.lease == null || item.lease.disposed()) return;
        if (!executeWorker(() -> {
            try { item.lease.close(); }
            catch (IOException cleanup) { main.postDelayed(() -> disposeUnattached(item), 1000); }
        })) main.postDelayed(() -> disposeUnattached(item), 1000);
    }
    private void send(ClipboardUriSource.Event event) {
        if (!ready() || !source.current(event)) return;
        ClipboardBridge.Probe probe = probe(); if (probe != null && !probe.permitUriOutbound(event.clip())) return;
        if (owned.size() >= 4 || outgoing != null && !outgoing.result.isDone()) {
            lastTransfer = CompletableFuture.failedFuture(new IOException("file_busy")); return;
        }
        Outgoing work = new Outgoing(event); outgoing = work; lastTransfer = work.result; owned.add(work);
        files.allocate(FileTransferNegotiation.Purpose.CLIPBOARD, event.ownerToken()).whenComplete((key, failure) -> main.post(() -> {
            work.allocating = false;
            if (failure != null || key == null) { retire(work, "file_allocate_failed"); return; }
            work.key = key;
            if (!current(work)) { retire(work, "stale_session"); return; }
            work.preparing = true;
            if (!executeWorker(() -> { try { prepare(work); } finally { work.preparing = false; if (work.cancelled) cleanupSource(work); } })) {
                work.preparing = false; retire(work, "clipboard_uri_worker_busy");
            }
        }));
    }
    private boolean current(Outgoing work) { return !closed && !work.cancelled && !work.result.isDone() && source.current(work.event) && (work.key == null || files.current(work.key)); }
    private void prepare(Outgoing work) {
        try {
            if (!current(work)) throw new IOException("stale_session");
            Uri uri = work.event.clip().getItemAt(0).getUri();
            String name = null; long size = -1;
            try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int named = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME), sized = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (named >= 0 && !cursor.isNull(named)) name = cursor.getString(named);
                    if (sized >= 0 && !cursor.isNull(sized)) size = cursor.getLong(sized);
                }
            }
            String mime = context.getContentResolver().getType(uri);
            if (name == null || mime == null) throw new IOException("clipboard_uri_metadata_unavailable");
            work.snapshot = ClipboardUriTransfer.prepare(context, work.event.clip(), work.key, key -> current(work) && key.equals(work.key), name, mime, size);
            if (!current(work)) throw new IOException("stale_session");
            CompletableFuture<Void> consent = files.awaitReceiver(work.key, work.snapshot.offer()); work.pending = consent;
            consent.whenComplete((ignored, failure) -> main.post(() -> {
                if (failure != null || !current(work)) { retire(work, "receive_consent_failed"); return; }
                CompletableFuture<ClipboardUriTransfer.Uploaded> upload = work.snapshot.sendAfterReceiverConsent(files.fileClient()); work.pending = upload;
                upload.whenComplete((uploaded, error) -> main.post(() -> {
                    if (error != null || uploaded == null || !current(work)) { retire(work, "clipboard_uri_upload_failed"); return; }
                    apply(work, uploaded);
                }));
            }));
        } catch (IOException | RuntimeException failure) { main.post(() -> retire(work, "clipboard_uri_source_unavailable")); }
    }
    private void apply(Outgoing work, ClipboardUriTransfer.Uploaded uploaded) {
        if (!current(work) || !uploaded.key().equals(work.key)) { retire(work, "stale_session"); return; }
        String request = "uri:" + work.key.transfer(); work.applying = true;
        Runnable timeout = () -> retire(work, "request_timeout"); main.postDelayed(timeout, 15_000);
        try {
            client.request(work.key.peer(), work.key.session(), "clipboard_uri_write", request,
                    ClipboardUriCodec.encode(new ClipboardUriCodec.Request(work.key.transfer(), uploaded.offer())), response -> {
                main.removeCallbacks(timeout);
                JSONObject result = response == null ? null : response.optJSONObject("result");
                if (current(work) && response != null && "ok".equals(response.optString("status")) && work.key.session().equals(response.optString("session_id"))
                        && request.equals(response.optString("request_id")) && result != null && "clipboard_uri_write".equals(result.optString("operation"))
                        && ClipboardUriCodec.matchesAck(result.optJSONObject("data"), work.key, uploaded.offer())) {
                    work.result.complete(new Receipt(work.key.peer(), work.key.session(), request, work.key.transfer(), uploaded.offer().size(), uploaded.offer().sha256()));
                    cleanupSource(work);
                } else retire(work, "clipboard_uri_remote_write_failed");
            });
        } catch (org.json.JSONException | RuntimeException failure) { main.removeCallbacks(timeout); retire(work, "invalid_clipboard_uri_payload"); }
    }
    private final BridgeClient.VendorExecutor uriExecutor = new BridgeClient.VendorExecutor() {
        @Override public void execute(JSONObject task, BridgeClient.TaskCompletion completion) {
            if (!ready()) { completion.fail("permission_denied"); return; }
            try {
                ClipboardUriCodec.Request payload = ClipboardUriCodec.decode(task.optJSONObject("payload"));
                FileTransferEngine.Key key = new FileTransferEngine.Key(task.optString("projection_id"), task.optString("session_id"), payload.transfer());
                Incoming item = incoming.get(key);
                if (item == null || !currentIncoming(item, item.local) || !item.attached || !item.prepared.metadata().offer().equals(payload.offer())) {
                    completion.fail("receive_consent_required"); return;
                }
                String wireRequest = task.optString("request_id"), binding = key.session() + ":" + wireRequest;
                if (!wireRequest.matches("[A-Za-z0-9_:-]{1,128}") || wireRequests.size() >= 256 && !wireRequests.containsKey(binding)) {
                    completion.fail("invalid_request"); return;
                }
                FileTransferEngine.Key prior = wireRequests.putIfAbsent(binding, key);
                if (prior != null && !prior.equals(key)) { completion.fail("request_id_reused"); return; }
                // The public task ID is a wire request number. The local single-use file import
                // has its own canonical identity; never pretend the source's Binder ID crossed the wire.
                String request = item.local.request();
                deliveries.execute(key.peer(), key.session(), request, payload, task.optLong("deadline_elapsed_ms")).whenComplete((result, failure) -> {
                    if (failure != null || result == null) completion.fail("native_clipboard_failed");
                    else if (!result.applied()) completion.fail(result.error());
                    else try { completion.success(ClipboardUriCodec.applied(key, request, payload.offer(), result)); }
                    catch (org.json.JSONException | RuntimeException invalid) { completion.fail("invalid_clipboard_uri_result"); }
                });
            } catch (RuntimeException invalid) { completion.fail("invalid_clipboard_uri_payload"); }
        }
        @Override public void cancel(String session, String request) {
            FileTransferEngine.Key key = wireRequests.get(session + ":" + request);
            if (key != null) deliveries.cancel(key.peer(), session, "uri:" + key.transfer());
        }
        @Override public void sessionChanged() { ClipboardUriBridge.this.sessionChanged(); }
    };
    CompletableFuture<Receipt> lastTransfer() { return lastTransfer == null ? null : lastTransfer.copy(); }
    private CompletableFuture<Receipt> observedTransfer() { return lastTransfer; }
    private void cancelOutgoing() { Outgoing work = outgoing; if (work != null && !work.result.isDone()) retire(work, "file_cancelled"); }
    private void retire(Outgoing work, String code) {
        work.cancelled = true; work.result.completeExceptionally(new IOException(code));
        if (work.pending != null && !work.pending.isDone()) work.pending.cancel(false);
        if (work.applying && work.key != null) client.cancel(work.key.session(), "uri:" + work.key.transfer(), ignored -> {});
        cleanupSource(work);
    }
    private void cleanupSource(Outgoing work) {
        if (work.allocating || work.preparing) { main.postDelayed(() -> cleanupSource(work), 1000); return; }
        if (!work.cleaning.compareAndSet(false, true)) return;
        if (!executeWorker(() -> {
            if (work.snapshot != null) try { work.snapshot.close(); work.snapshot = null; }
            catch (IOException failure) { work.cleaning.set(false); main.postDelayed(() -> cleanupSource(work), 1000); return; }
            if (work.key != null && files.current(work.key)) files.release(work.key).whenComplete((released, failure) -> {
                if (failure == null && Boolean.TRUE.equals(released) || !files.current(work.key)) owned.remove(work);
                else { work.cleaning.set(false); main.postDelayed(() -> cleanupSource(work), 1000); }
            });
            else owned.remove(work);
        })) { work.cleaning.set(false); main.postDelayed(() -> cleanupSource(work), 1000); }
    }
    private boolean executeWorker(Runnable task) {
        try { worker.execute(task); return true; }
        catch (RuntimeException full) { module.log(android.util.Log.WARN, "QiangJian", "clipboard_uri_worker_busy"); return false; }
    }
    private void sessionChanged() {
        if (closed) return;
        Outgoing work = outgoing; if (work != null && !current(work)) retire(work, "stale_session");
        source.update(); paste.sessionChanged(); deliveries.sessionChanged();
        for (Incoming item : incoming.values()) if (!currentIncoming(item, item.local)) revokeIncoming(item);
        wireRequests.entrySet().removeIf(entry -> !files.current(entry.getValue()));
    }
    private static void mainThread() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("clipboard_requires_main"); }
    @Override public void close() {
        mainThread(); if (closed) return; closed = true; main.removeCallbacks(refresh);
        source.close(); cancelOutgoing(); paste.close();
        for (Incoming item : incoming.values()) revokeIncoming(item);
        deliveries.close(); if (uriProbe != null) uriProbe.close(); endpoint.close();
        if (subscription != null) subscription.close(); if (executor != null) executor.close(); if (registration != null) registration.close();
        for (Outgoing work : owned) retire(work, "file_cancelled");
        main.post(finishClose);
    }
    private void finishClose() {
        if (owned.isEmpty() && incoming.values().stream().allMatch(item -> !item.preparing && (item.lease == null || item.lease.disposed()))
                && worker.getActiveCount() == 0 && worker.getQueue().isEmpty()) worker.shutdown();
        else main.postDelayed(finishClose, 1000);
    }
}
