package io.github.qiangjian.connect;

import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;

/** Integration against shared-contract v3. Installed only by the shared owner after whitelisting. */
final class DeviceFinderBinding implements BridgeClient.VendorExecutor, AutoCloseable {
    private final BridgeClient client;
    private final DeviceFinderEndpoint endpoint;
    private OplusFinderChannelAdapter nativeSender;
    private BridgeClient.Subscription registration, projection;
    private volatile boolean closed;

    DeviceFinderBinding(BridgeClient client, ClassLoader loader, int source) throws Exception {
        this(client, loader, source, null);
    }

    DeviceFinderBinding(BridgeClient client, ClassLoader loader, int source, io.github.libxposed.api.XposedModule module) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("finder_main_thread_required");
        if (client.context() == null) throw new IllegalStateException("finder_context_unavailable");
        this.client = client;
        endpoint = new DeviceFinderEndpoint(client.context(), loader, source, module);
        try {
            registration = client.registerExecutor("device_finder", this);
            projection = client.addProjectionListener(this::update);
            update(client.projection());
            if (source == 2) try {
                nativeSender = new OplusFinderChannelAdapter(module, client, loader, () -> !closed);
            } catch (Exception unavailable) {
                // Sending is independent of the already verified receiver. No cloud fallback or fake UI.
                android.util.Log.w("QiangJianFinder", "native_sender_unavailable " + unavailable.getClass().getSimpleName());
            }
        } catch (Exception failure) { close(); throw failure; }
    }

    private void update(JSONObject selected) {
        try {
            if (closed || selected == null || !supports(selected)) endpoint.bind(null, null);
            else {
                JSONObject real = selected.optJSONObject("real");
                endpoint.bind(selected.optString("session_id"), real == null ? null : real.optString("name"));
            }
        } catch (Exception cleanupFailed) {
            // Do not publish an executor that failed to release an old session's resources.
            if (registration != null) { registration.close(); registration = null; }
            endpoint.cancel();
        }
    }

    @Override public void execute(JSONObject task, BridgeClient.TaskCompletion completion) {
        JSONObject selected = client.projection();
        String session = task.optString("session_id"), request = task.optString("request_id");
        if (closed || selected == null || !session.equals(selected.optString("session_id"))
                || !task.optString("projection_id").equals(selected.optString("id"))) {
            completion.fail("stale_session"); return;
        }
        if (!supports(selected)) { completion.fail("permission_denied"); return; }
        long deadline = task.optLong("deadline_elapsed_ms");
        if (SystemClock.elapsedRealtime() >= deadline) { completion.fail("request_timeout"); return; }
        endpoint.execute(session, request, deadline, task.optJSONObject("payload"), result -> {
            if (!"ok".equals(result.optString("status"))) { completion.fail(result.optString("code", "finder_execution_failed")); return; }
            JSONObject live = client.projection();
            if (closed || live == null || !session.equals(live.optString("session_id"))
                    || !task.optString("projection_id").equals(live.optString("id")) || !supports(live)
                    || SystemClock.elapsedRealtime() >= deadline) {
                endpoint.cancel(session, request);
                completion.fail("session_expired"); return;
            }
            result.remove("status");
            completion.success(result);
        });
    }

    private static boolean supports(JSONObject selected) {
        org.json.JSONArray operations = selected.optJSONArray("operations");
        if (operations != null) for (int i = 0; i < operations.length(); i++)
            if ("device_finder".equals(operations.optString(i))) return true;
        return false;
    }

    @Override public void cancel(String sessionId, String requestId) { endpoint.cancel(sessionId, requestId); }
    @Override public void sessionChanged() { update(null); }
    @Override public void close() throws Exception {
        if (closed) return;
        closed = true;
        if (projection != null) { projection.close(); projection = null; }
        if (registration != null) { registration.close(); registration = null; }
        try { if (nativeSender != null) { nativeSender.close(); nativeSender = null; } }
        finally { endpoint.close(); }
    }
}
