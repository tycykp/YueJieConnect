package io.github.qiangjian.connect;

import android.os.SystemClock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** v3 vendor executor. Registration/operation allowlist belongs to native-pairing. */
final class MediaControlTasks implements BridgeClient.VendorExecutor, AutoCloseable {
    static final String OPERATION = "media_control";
    private final BridgeClient client;
    private final MediaControlEndpoint endpoint;
    interface Authorization {
        /** Must bind this task's exact peer/session and native cast lease or explicit media grant. */
        boolean allowed(String projectionId, String sessionId, long castStreamId, int castScreenId, MediaControlSession.Request request);
    }
    private final Authorization authorization;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentHashMap<Key, AtomicBoolean> live = new ConcurrentHashMap<>();
    private record Key(String session, String request) {}
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), runnable -> { Thread thread = new Thread(runnable, "qj-media-control"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());

    MediaControlTasks(BridgeClient client, MediaControlEndpoint endpoint, Authorization authorization) {
        this.client = java.util.Objects.requireNonNull(client); this.endpoint = java.util.Objects.requireNonNull(endpoint);
        this.authorization = java.util.Objects.requireNonNull(authorization);
    }

    @Override public void execute(JSONObject task, BridgeClient.TaskCompletion completion) {
        if (closed.get()) { completion.fail("executor_unavailable"); return; }
        Key key;
        String projectionId;
        JSONObject payload;
        long deadline;
        MediaControlSession.Request decoded;
        MediaControlWire.Payload scoped;
        try {
            if (!OPERATION.equals(task.getString("operation"))) throw new IllegalArgumentException();
            key = new Key(task.getString("session_id"), task.getString("request_id"));
            projectionId = task.getString("projection_id");
            if (key.session.isEmpty() || key.request.isEmpty() || projectionId.isEmpty()) throw new IllegalArgumentException();
            deadline = task.getLong("deadline_elapsed_ms");
            scoped = MediaControlWire.payload(new JSONObject(task.getJSONObject("payload").toString()));
            payload = scoped.control(); decoded = scoped.request();
        } catch (Exception invalid) { completion.fail("invalid_media_request"); return; }
        if (deadline <= SystemClock.elapsedRealtime()) { completion.fail("request_timeout"); return; }
        AtomicBoolean cancelled = new AtomicBoolean();
        if (live.putIfAbsent(key, cancelled) != null) { completion.fail("duplicate_request"); return; }
        try {
            worker.execute(() -> {
                try {
                    if (SystemClock.elapsedRealtime() >= deadline) { completion.fail("request_timeout"); return; }
                    JSONObject result = endpoint.execute(payload,
                            () -> !closed.get() && current(projectionId, key.session) && SystemClock.elapsedRealtime() < deadline
                                    && authorization.allowed(projectionId, key.session, scoped.streamId(), scoped.screenId(), decoded),
                            cancelled::get);
                    if (!current(projectionId, key.session) || closed.get()) { completion.fail("stale_session"); return; }
                    if (SystemClock.elapsedRealtime() >= deadline) { completion.fail("request_timeout"); return; }
                    if (!authorization.allowed(projectionId, key.session, scoped.streamId(), scoped.screenId(), decoded)) { completion.fail("permission_denied"); return; }
                    MediaControlSession.Result verified = MediaControlWire.result(result);
                    if (verified.confirmed()) {
                        completion.success(result);
                    } else {
                        completion.fail(verified.code());
                    }
                } catch (SecurityException denied) { completion.fail("permission_denied"); }
                catch (Exception failed) { completion.fail("media_execution_failed"); }
                finally { live.remove(key, cancelled); }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            live.remove(key, cancelled);
            completion.fail("busy");
        }
    }
    private boolean current(String id, String session) {
        JSONObject projection = client.projection();
        return projection != null && id.equals(projection.optString("id")) && session.equals(projection.optString("session_id"));
    }
    @Override public void cancel(String sessionId, String requestId) {
        AtomicBoolean flag = live.get(new Key(sessionId, requestId));
        if (flag != null) flag.set(true);
    }
    @Override public void sessionChanged() {
        live.values().forEach(flag -> flag.set(true));
        endpoint.invalidate();
    }
    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            sessionChanged();
            // Drain queued jobs into stale-session/closed failure; never drop their completions.
            worker.shutdown();
        }
    }
}
