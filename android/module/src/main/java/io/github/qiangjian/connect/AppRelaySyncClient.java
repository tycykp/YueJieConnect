package io.github.qiangjian.connect;

import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** Native source requests may outlive one RPC; a pending source is never launch success. */
final class AppRelaySyncClient implements AutoCloseable {
    private static final class Pending {
        final String peer, session, pkg, token, id = "relay-sync-" + UUID.randomUUID();
        final long started, timeout;
        final CompletableFuture<AppRelayTasks.Result> result = new CompletableFuture<>();
        boolean cancelled;
        Pending(String peer, String session, String pkg, String token, long started) {
            this.peer = peer; this.session = session; this.pkg = pkg; this.token = token; this.started = started;
            timeout = token.isEmpty() ? 15000 : 30 * 60 * 1000;
        }
    }
    private final AppRelaySender.Transport transport;
    private final AppRelaySender.Scheduler queue;
    private final LongSupplier clock;
    private final Map<String, Pending> pending = new HashMap<>();
    private boolean closed;
    AppRelaySyncClient(AppRelaySender.Transport transport) { this(transport, androidQueue(), () -> System.nanoTime()/1000000); }
    AppRelaySyncClient(AppRelaySender.Transport transport, AppRelaySender.Scheduler queue, LongSupplier clock) {
        this.transport = transport; this.queue = queue; this.clock = clock;
    }
    private static AppRelaySender.Scheduler androidQueue() {
        Handler main = new Handler(Looper.getMainLooper());
        return new AppRelaySender.Scheduler() {
            public void execute(Runnable work) { main.post(work); }
            public void later(Runnable work, long millis) { main.postDelayed(work, millis); }
        };
    }
    CompletableFuture<AppRelayTasks.Result> request(String peer, String session, String pkg, String token) {
        AppRelaySync.validate("native-click", pkg); AppRelaySync.validateOwnerToken(token);
        Pending item = new Pending(peer, session, pkg, token, clock.getAsLong());
        item.result.whenComplete((ignored, error) -> { if (item.result.isCancelled()) queue.execute(() -> { cancelRemote(item); pending.remove(item.id, item); }); });
        queue.execute(() -> {
            if (item.result.isDone()) return;
            if (closed || !transport.current(peer, session)) { fail(item, "session_revoked"); return; }
            if (pending.size() >= 8) { fail(item, "relay_busy"); return; }
            pending.put(item.id, item); call(item, "sync");
        });
        return item.result;
    }
    private void call(Pending item, String action) {
        if (item.result.isDone()) return;
        if (closed || !transport.current(item.peer, item.session)) { fail(item, "session_revoked"); return; }
        if (clock.getAsLong() - item.started >= item.timeout) { cancelRemote(item); fail(item, "request_timeout"); return; }
        try {
            JSONObject payload = new JSONObject().put("action", action).put("request_id", item.id);
            if ("sync".equals(action)) {
                payload.put("source_package", item.pkg);
                if (!item.token.isEmpty()) payload.put("native_owner_token", item.token);
            }
            transport.request(item.peer, item.session, payload).whenComplete((reply, error) -> queue.execute(() -> {
                if (item.result.isDone()) return;
                if (error != null || !transport.current(item.peer, item.session)) { cancelRemote(item); fail(item, "remote_request_failed"); return; }
                try {
                    AppRelayTasks.Result result = AppRelayCodec.decodeResult(reply);
                    if (!item.id.equals(result.requestId())) throw new IllegalArgumentException("invalid_remote_receipt");
                    if (result.state() == AppRelayTasks.State.LAUNCHING && "native_source_requested".equals(result.code())
                            && item.pkg.equals(result.targetPackage()) && result.taskId() == -1) {
                        queue.later(() -> call(item, item.cancelled ? "cancel" : "status"), 500); return;
                    }
                    if (result.state() != AppRelayTasks.State.FAILED && result.state() != AppRelayTasks.State.CANCELLED
                            && (result.state() != AppRelayTasks.State.COMPLETED || !"foreground_observed".equals(result.code()) || result.taskId() < 0))
                        throw new IllegalArgumentException("invalid_remote_receipt");
                    pending.remove(item.id, item); item.result.complete(result);
                } catch (Exception invalid) { cancelRemote(item); fail(item, "invalid_remote_receipt"); }
            }));
        } catch (Exception invalid) { fail(item, "remote_request_failed"); }
    }
    private void cancelRemote(Pending item) {
        try { transport.request(item.peer, item.session, new JSONObject().put("action", "cancel").put("request_id", item.id)); }
        catch (Exception ignored) { /* Only a terminal native result denotes cancellation. */ }
    }
    private void fail(Pending item, String code) {
        pending.remove(item.id, item); item.result.complete(new AppRelayTasks.Result(item.id, AppRelayTasks.State.FAILED, code, "", -1));
    }
    @Override public void close() {
        queue.execute(() -> {
            closed = true;
            for (Pending item : List.copyOf(pending.values())) { cancelRemote(item); fail(item, "session_revoked"); }
        });
    }
}
