package io.github.yuejie.connect;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import static io.github.yuejie.connect.TelephonySyncController.Session;
import static io.github.yuejie.connect.TelephonySyncWire.*;

/** Bound to one authenticated native executor and one session. Public owner installs only after typed allowlisting. */
final class TelephonySyncBridge implements AutoCloseable, BridgeClient.VendorExecutor {
    private final BridgeClient client;
    private final TelephonySyncEndpoint endpoint;
    private final Set<String> operations;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<BridgeClient.Subscription> registrations = new ArrayList<>();
    private BridgeClient.Subscription projection;
    private boolean installed;
    private volatile boolean closed;
    private final Runnable tick = new Runnable() {
        @Override public void run() { if (!closed) { endpoint.tick(); if (!current()) close(); else main.postDelayed(this, 250); } }
    };
    TelephonySyncBridge(BridgeClient client, TelephonySyncEndpoint endpoint, Set<String> operations) {
        this.client = java.util.Objects.requireNonNull(client); this.endpoint = java.util.Objects.requireNonNull(endpoint);
        this.operations = Set.copyOf(operations);
        if (operations.isEmpty() || !OPERATIONS.containsAll(operations)) throw new IllegalArgumentException("invalid_telephony_operations");
    }
    void install() {
        if (!main.getLooper().isCurrentThread() || closed || installed) throw new IllegalStateException("telephony_lifecycle");
        if (!current()) throw new IllegalStateException("native_telephony_not_ready");
        installed = true;
        try {
            for (String operation : operations) registrations.add(client.registerExecutor(operation, this));
            projection = client.addProjectionListener(ignored -> { if (!current()) close(); });
            main.post(tick);
        } catch (RuntimeException failure) { close(); throw failure; }
    }
    private boolean current() {
        if (closed || !endpoint.operations().containsAll(operations)) return false;
        JSONObject value = client.projection(); Session session = endpoint.session();
        if (value == null || !session.id().equals(value.optString("session_id")) || !session.peer().equals(value.optString("id"))) return false;
        for (String operation : operations) if (!client.hasLocalGrant(operation)) return false;
        return true;
    }
    @Override public void execute(JSONObject task, BridgeClient.TaskCompletion completion) {
        try {
            if (!current() || task == null || !endpoint.session().id().equals(task.getString("session_id"))
                    || !endpoint.session().peer().equals(task.getString("projection_id"))) throw new Failure("session_invalid");
            String operation = task.getString("operation"), request = task.getString("request_id");
            if (!operations.contains(operation)) throw new Failure("permission_denied");
            Object deadline = task.get("deadline_elapsed_ms");
            if (!(deadline instanceof Integer) && !(deadline instanceof Long)) throw new Failure("invalid_payload");
            long end = ((Number) deadline).longValue(); if (end <= SystemClock.elapsedRealtime()) throw new Failure("deadline_expired");
            endpoint.execute(endpoint.session(), operation, request, TelephonySyncBridgeTransport.map(task.getJSONObject("payload")), end)
                    .whenComplete((data, failure) -> {
                        if (failure == null && current()) completion.success(new JSONObject(data));
                        else {
                            Throwable cause = failure;
                            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
                            completion.fail(cause instanceof Failure error ? error.code : failure == null ? "session_invalid" : "outcome_unknown");
                        }
                    });
        } catch (Exception invalid) { completion.fail(invalid instanceof Failure f ? f.code : "invalid_payload"); }
    }
    @Override public void cancel(String sessionId, String requestId) {
        if (endpoint.session().id().equals(sessionId)) endpoint.cancel(endpoint.session(), requestId);
    }
    @Override public void sessionChanged() { close(); }
    @Override public void close() {
        if (!main.getLooper().isCurrentThread()) { main.post(this::close); return; }
        if (closed) return; closed = true; main.removeCallbacks(tick); endpoint.close();
        if (projection != null) { projection.close(); projection = null; }
        for (BridgeClient.Subscription registration : registrations) registration.close(); registrations.clear();
    }
}
