package io.github.yuejie.connect;

import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Main-thread local receive table. A wire request can only consume a previously selected native lease.
 * Owns at most four leases, including successful paste files and pending cleanup; never authorizes one. */
final class ClipboardUriRoutes implements AutoCloseable {
    private record Entry(FileTransferReceiveLease<Uri> lease, ClipboardUriReceiver receiver) {}
    private record Task(FileTransferEngine.Key key, FileTransferEngine.Offer offer,
                        CompletableFuture<ClipboardUriReceiver.Result> result) {}
    private final Map<FileTransferEngine.Key, Entry> leases = new HashMap<>();
    private final Map<String, Task> tasks = new HashMap<>();
    private final Map<FileTransferEngine.Key, String> owners = new HashMap<>();
    private final ClipboardNativeContent endpoint;
    private final ClipboardEchoGuard echoes;
    private final BooleanSupplier allowed;
    private final Supplier<JSONObject> projection;
    private boolean closed;

    ClipboardUriRoutes(ClipboardNativeContent endpoint, ClipboardEchoGuard echoes, BooleanSupplier allowed,
            Supplier<JSONObject> projection) {
        this.endpoint = Objects.requireNonNull(endpoint); this.echoes = Objects.requireNonNull(echoes);
        this.allowed = Objects.requireNonNull(allowed); this.projection = Objects.requireNonNull(projection);
    }

    /** true transfers cleanup ownership; false leaves ownership with the native receive owner.
     * Call after actual lease.accepted() success and before exposing the selected receive to the peer.
     * The local decision gate must additionally bind clipboard purpose and its native grant lifetime. */
    boolean attach(FileTransferReceiveLease<Uri> lease) {
        main(); Objects.requireNonNull(lease); prune();
        if (!current(lease.key()) || leases.size() >= FileTransferEngine.MAX_TRANSFERS || leases.containsKey(lease.key())
                || !lease.accepted().isDone() || lease.accepted().isCompletedExceptionally() || !lease.current()) return false;
        leases.put(lease.key(), new Entry(lease, new ClipboardUriReceiver(lease, endpoint, echoes, allowed, projection)));
        return true;
    }

    CompletableFuture<ClipboardUriReceiver.Result> execute(String peer, String session, String request,
            ClipboardUriCodec.Request payload, long deadlineElapsedMs) {
        main(); Objects.requireNonNull(payload); prune();
        FileTransferEngine.Key key = new FileTransferEngine.Key(peer, session, payload.transfer());
        if (request == null || !request.matches("[A-Za-z0-9_:-]{1,128}")) return failure(key, request, "invalid_request");
        if (!current(key)) return failure(key, request, "stale_session");
        String id = session + ":" + request;
        Task prior = tasks.get(id);
        if (prior != null) return prior.key().equals(key) && prior.offer().equals(payload.offer())
                ? prior.result().copy() : failure(key, request, "request_id_reused");
        long now = SystemClock.elapsedRealtime();
        if (deadlineElapsedMs <= now || deadlineElapsedMs - now > 30_000) return failure(key, request, "expired_or_invalid_request");
        if (tasks.size() >= 256) return failure(key, request, "clipboard_uri_request_limit");
        Entry local = leases.get(key);
        if (local == null) return failure(key, request, "receive_consent_required");
        if (!local.lease().offer().equals(payload.offer())) return failure(key, request, "consent_metadata_mismatch");
        if (owners.containsKey(key)) return failure(key, request, "clipboard_uri_already_started");
        CompletableFuture<ClipboardUriReceiver.Result> result = local.receiver().execute(request, deadlineElapsedMs);
        owners.put(key, id);
        tasks.put(id, new Task(key, payload.offer(), result));
        return result.copy();
    }

    CompletableFuture<Boolean> cancel(String peer, String session, String request) {
        main(); Task task = tasks.get(session + ":" + request);
        if (task == null || !task.key().peer().equals(peer)) return CompletableFuture.completedFuture(false);
        Entry entry = leases.get(task.key());
        return entry == null ? CompletableFuture.completedFuture(false) : entry.receiver().cancel();
    }

    /** Local native decision/grant revocation. Successful paste files remain owned until cleaned. */
    void revoke(FileTransferEngine.Key key) {
        main(); Entry entry = leases.get(key); if (entry != null) entry.receiver().close();
    }

    void sessionChanged() {
        main();
        for (Entry entry : leases.values()) entry.receiver().sessionChanged();
        tasks.entrySet().removeIf(entry -> !current(entry.getValue().key()));
        owners.keySet().removeIf(key -> !current(key));
        prune();
    }
    private void prune() { leases.entrySet().removeIf(entry -> entry.getValue().receiver().disposed()); }
    private boolean current(FileTransferEngine.Key key) {
        try {
            JSONObject p = projection.get();
            return !closed && allowed.getAsBoolean() && p != null && key.peer().equals(p.optString("id"))
                    && key.session().equals(p.optString("session_id"));
        } catch (RuntimeException unavailable) { return false; }
    }
    private static CompletableFuture<ClipboardUriReceiver.Result> failure(FileTransferEngine.Key key, String request, String code) {
        return CompletableFuture.completedFuture(new ClipboardUriReceiver.Result(key.peer(), key.session(), request,
                key.transfer(), 0, null, code));
    }
    private static void main() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("clipboard_uri_routes_requires_main");
    }
    @Override public void close() {
        main(); closed = true;
        for (Entry entry : leases.values()) entry.receiver().close();
        tasks.clear(); // Receivers retain their exact lease and cleanup retry after close.
        owners.clear();
    }
}
