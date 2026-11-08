package io.github.qiangjian.connect;

import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/** Asynchronous remote prepare/launch/observe. Transport authenticates the outer receipt as well. */
final class AppRelaySender implements AutoCloseable {
    interface Transport {
        boolean current(String peer, String session);
        CompletableFuture<JSONObject> request(String peer, String session, JSONObject payload);
    }
    interface Scheduler { void execute(Runnable work); void later(Runnable work, long delayMillis); }
    private static final class Pending {
        final String peer, session;
        final AppRelayRequest request;
        final CompletableFuture<AppRelayTasks.Result> future = new CompletableFuture<>();
        boolean cancelled;
        boolean prepared;
        int polls;
        Pending(String peer, String session, AppRelayRequest request) { this.peer = peer; this.session = session; this.request = request; }
    }
    private final Transport transport;
    private final Scheduler main;
    private final Map<String, Pending> pending = new HashMap<>();
    private boolean closed;
    AppRelaySender(Transport transport) { this(transport, androidScheduler()); }
    AppRelaySender(Transport transport, Scheduler scheduler) { this.transport = transport; this.main = scheduler; }
    private static Scheduler androidScheduler() {
        Handler handler = new Handler(Looper.getMainLooper());
        return new Scheduler() {
            @Override public void execute(Runnable work) { handler.post(work); }
            @Override public void later(Runnable work, long delay) { handler.postDelayed(work, delay); }
        };
    }

    CompletableFuture<AppRelayTasks.Result> send(String peer, String session, AppRelayRequest request) {
        Pending item = new Pending(peer, session, request);
        item.future.whenComplete((value, error) -> { if (item.future.isCancelled()) cancel(request.requestId()); });
        main.execute(() -> {
            if (item.future.isCancelled()) return;
            if (closed || !transport.current(peer, session)) { fail(item, "session_revoked"); return; }
            if (pending.size() >= 8 || pending.containsKey(request.requestId())) { fail(item, "relay_busy"); return; }
            pending.put(request.requestId(), item);
            main.later(() -> {
                if (!item.future.isDone()) { cancelRemote(item); fail(item, "request_timeout"); }
            }, 15000);
            call(item, "prepare");
        });
        return item.future;
    }
    void cancel(String requestId) {
        main.execute(() -> {
            Pending item = pending.get(requestId);
            if (item != null) {
                item.cancelled = true;
                if (item.future.isCancelled()) { cancelRemote(item); pending.remove(requestId, item); }
                else if (item.prepared) call(item, "cancel");
            }
        });
    }
    private void call(Pending item, String action) {
        if (item.future.isDone()) return;
        if (!transport.current(item.peer, item.session)) { fail(item, "session_revoked"); return; }
        try {
            JSONObject payload = new JSONObject().put("action", action).put("request_id", item.request.requestId());
            if ("prepare".equals(action)) payload.put("handoff", AppRelayCodec.encode(item.request));
            transport.request(item.peer, item.session, payload).whenComplete((reply, error) -> main.execute(() -> {
                if (item.future.isDone()) { if (item.future.isCancelled()) { cancelRemote(item); pending.remove(item.request.requestId(), item); } return; }
                if (!transport.current(item.peer, item.session)) { fail(item, "session_revoked"); return; }
                if (error != null) { fail(item, "remote_request_failed"); return; }
                try {
                    AppRelayTasks.Result result = AppRelayCodec.decodeResult(reply);
                    if (!item.request.requestId().equals(result.requestId())) throw new IllegalArgumentException("invalid_remote_receipt");
                    AppRelayTasks.State state = result.state();
                    if ("too_late_to_cancel".equals(result.code())) { call(item, "status"); return; }
                    if (state == AppRelayTasks.State.PREPARED) { item.prepared = true; call(item, item.cancelled ? "cancel" : "launch"); return; }
                    if (state == AppRelayTasks.State.LAUNCHING) {
                        if (++item.polls > 48) { cancelRemote(item); fail(item, "launch_unverified"); return; }
                        main.later(() -> call(item, "status"), 250); return;
                    }
                    if (state == AppRelayTasks.State.COMPLETED && (result.taskId() < 0 || !"foreground_observed".equals(result.code())
                            || (item.request.kind() != AppRelayRequest.Kind.BROWSER && !item.request.targets().contains(result.targetPackage()))))
                        throw new IllegalArgumentException("invalid_remote_receipt");
                    pending.remove(item.request.requestId(), item); item.future.complete(result);
                } catch (Exception malformed) { fail(item, "invalid_remote_receipt"); }
            }));
        } catch (Exception error) { fail(item, "remote_request_failed"); }
    }
    private void cancelRemote(Pending item) {
        try { transport.request(item.peer, item.session, new JSONObject().put("action", "cancel").put("request_id", item.request.requestId())); }
        catch (Exception ignored) { /* No cancellation success is inferred from a best-effort request. */ }
    }
    private void fail(Pending item, String code) {
        pending.remove(item.request.requestId(), item);
        item.future.complete(new AppRelayTasks.Result(item.request.requestId(), AppRelayTasks.State.FAILED, code, "", -1));
    }
    @Override public void close() {
        main.execute(() -> {
            closed = true;
            for (Pending item : java.util.List.copyOf(pending.values())) { cancelRemote(item); fail(item, "session_revoked"); }
        });
    }
}
