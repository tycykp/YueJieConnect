package io.github.qiangjian.connect;

import android.os.SystemClock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Shared owner routes only the three typed notification operations here, in the Runtime's process. */
final class NotificationSyncEndpoint implements BridgeClient.VendorExecutor, AutoCloseable {
    private record Task(String session, String request) {}
    private final NotificationSyncRuntime runtime;
    private final NotificationSyncIngress ingress;
    private final Map<Task, String> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "qj-notification-expiry"); thread.setDaemon(true); return thread;
    });
    private volatile boolean closed;
    NotificationSyncEndpoint(NotificationSyncRuntime runtime, NotificationSyncRuntime.Grants grants) {
        this.runtime = runtime;
        ingress = new NotificationSyncIngress(new NotificationSyncIngress.Executor() {
            @Override public java.util.concurrent.CompletableFuture<byte[]> execute(String peer, String session, String request,
                    String operation, byte[] bytes, long deadline) {
                return runtime.execute(peer, session, request, operation, bytes, deadline);
            }
            @Override public void cancel(String session, String request) { runtime.cancel(session, request); }
        }, grants::allows, SystemClock::elapsedRealtime);
        expiry.scheduleWithFixedDelay(ingress::maintain, 1, 1, TimeUnit.SECONDS);
    }
    void onProjection(String peer, String session) {
        if (closed) return;
        runtime.onProjection(peer, session); ingress.onProjection(peer, session);
    }
    void authorizationChanged() {
        ingress.authorizationChanged(); runtime.authorizationChanged();
    }
    @Override public synchronized void execute(JSONObject task, BridgeClient.TaskCompletion completion) {
        Task key = null;
        try {
            if (closed) throw new IllegalStateException();
            String session = task.getString("session_id"), request = task.getString("request_id");
            if (request.isEmpty() || request.length() > 128) throw new IllegalArgumentException();
            Task candidate = new Task(session, request);
            JSONObject payload = task.getJSONObject("payload");
            var part = NotificationSyncFragments.parse(payload);
            if (pending.size() >= 32 || pending.putIfAbsent(candidate, part.transfer()) != null) throw new IllegalStateException();
            key = candidate;
            Task exact = key;
            ingress.accept(task.getString("projection_id"), session, task.getString("operation"), payload,
                    task.getLong("deadline_elapsed_ms")).whenComplete((result, error) -> {
                        pending.remove(exact, part.transfer());
                        if (error != null || closed) completion.fail("notification_execution_failed");
                        else completion.success(result);
                    });
        } catch (org.json.JSONException | RuntimeException failure) {
            if (key != null) pending.remove(key);
            completion.fail("notification_request_rejected");
        }
    }
    @Override public void cancel(String session, String request) {
        String transfer = pending.get(new Task(session, request));
        if (transfer != null) ingress.cancel(session, transfer);
    }
    @Override public void sessionChanged() {
        // BridgeClient also invokes this when ANY one executor registration is removed. That may
        // leave the visible projection unchanged (e.g. an ungranted action operation was removed).
        // Reset effects/buffers without losing peer identity; the projection listener owns unbinding.
        authorizationChanged();
    }
    @Override public void close() {
        if (closed) return; closed = true; expiry.shutdownNow(); ingress.close(); pending.clear();
        // Runtime is owned by the service/native adapter and is closed by that lifecycle owner.
    }
}
