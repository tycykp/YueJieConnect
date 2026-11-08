package io.github.qiangjian.connect;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Delegated ringfind branch for XiaomiProtocolAdapter's existing SharedChannel hooks. */
final class XiaomiFinderAdapter implements AutoCloseable {
    interface Transport { JSONObject request(String id, String expectedSession, String operation, String requestId, JSONObject payload); }
    private final Transport transport;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong sequence = new AtomicLong();
    private final String requestPrefix = "finder-" + java.util.UUID.randomUUID() + "-";
    private final Map<Object, String> connections = new IdentityHashMap<>();
    private final ThreadPoolExecutor monitorWorker = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), r -> { Thread t = new Thread(r, "qj-finder-state"); t.setDaemon(true); return t; });
    private volatile JSONObject projection;
    private volatile boolean closed;
    private Context notifyContext;
    private String monitoringSession;
    private final Runnable poll = () -> {
        try { monitorWorker.execute(this::pollState); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { }
    };

    XiaomiFinderAdapter(Transport transport) { this.transport = transport; }

    synchronized void update(JSONObject next) {
        String before = projection == null ? "" : projection.optString("session_id");
        String after = next == null ? "" : next.optString("session_id");
        if (!before.equals(after) || next==null || !supports(next)
                || projection!=null&&!projection.optString("id").equals(next.optString("id"))) {
            connections.clear();
            monitoringSession = null;
            notifyContext = null;
            main.removeCallbacks(poll);
        }
        projection = next;
    }

    boolean owns(String caller, String id) {
        JSONObject p = projection;
        return !closed && "ringfind".equals(caller) && p != null && id != null && id.equals(p.optString("id"));
    }

    int connect(Object receiver, String id) {
        JSONObject p = projection;
        if (!owns("ringfind", id) || !supports(p)) return -12;
        String session = p.optString("session_id");
        JSONObject result = request(id, session, "state", 0);
        synchronized (this) {
            if (!matches(id, session, result)) return -11;
            connections.put(receiver, session);
        }
        return 0;
    }

    int send(Object receiver, Context context, String id, byte[] bytes) {
        XiaomiFinderCodec.Command command;
        try { command = XiaomiFinderCodec.decode(bytes); }
        catch (IllegalArgumentException invalid) { return -12; }
        String session;
        synchronized (this) { session = connections.get(receiver); }
        if (session == null || !owns("ringfind", id)) return -12;
        JSONObject reply = request(id, session, command.action(), "start".equals(command.action()) ? 10_000 : 0);
        synchronized (this) {
            if (!matches(id, session, reply) || !session.equals(connections.get(receiver))) return -11;
            JSONObject data = data(reply);
            if (data == null || !(data.opt("ringing") instanceof Boolean)) return -11;
            boolean ringing = data.optBoolean("ringing");
            deliver(context, id, command.method(), XiaomiFinderCodec.nativeStatus(command.method(), ringing));
            deliver(context, id, XiaomiFinderCodec.NOTIFY, ringing ? 100 : 101);
            if (ringing) {
                notifyContext = context.getApplicationContext();
                monitoringSession = session;
                main.removeCallbacks(poll);
                main.postDelayed(poll, 1000);
            } else { monitoringSession = null; notifyContext = null; main.removeCallbacks(poll); }
        }
        return 0;
    }

    synchronized void release(Object receiver) {
        String session = connections.remove(receiver);
        JSONObject p = projection;
        if (connections.isEmpty() && session != null && p != null && session.equals(monitoringSession)) {
            String id = p.optString("id");
            try { monitorWorker.execute(() -> stopIfReleased(id, session)); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { /* bounded remote timeout remains */ }
        }
    }

    private synchronized void stopIfReleased(String id, String session) {
        JSONObject selected = projection;
        if (closed || !connections.isEmpty() || selected == null || !id.equals(selected.optString("id"))
                || !session.equals(selected.optString("session_id")) || !session.equals(monitoringSession)) return;
        // Keep connection publication and this stop ordered: a newly connected card must not
        // start before the previous card's delayed release finishes stopping its own alarm.
        request(id, session, "stop", 0);
    }

    private void pollState() {
        JSONObject p = projection;
        String session;
        synchronized (this) { session = monitoringSession; }
        if (p == null || session == null) return;
        String id = p.optString("id");
        JSONObject reply = request(id, session, "state", 0);
        synchronized (this) {
            if (!session.equals(monitoringSession)) return;
            JSONObject state = data(reply);
            if (!matches(id, session, reply) || state == null || !(state.opt("ringing") instanceof Boolean)) {
                // OEM 300 means remote disconnected; 304 instead shuts down the request service.
                if (notifyContext != null) deliver(notifyContext, id, XiaomiFinderCodec.NOTIFY, 300);
                monitoringSession = null;
                notifyContext = null;
                return;
            }
            boolean ringing = state.optBoolean("ringing");
            if (notifyContext != null) deliver(notifyContext, id, XiaomiFinderCodec.NOTIFY, ringing ? 100 : 101);
            if (ringing) main.postDelayed(poll, 1000);
            else { monitoringSession = null; notifyContext = null; }
        }
    }

    private JSONObject request(String id, String session, String action, long duration) {
        try {
            JSONObject payload = new JSONObject().put("action", action);
            if (duration > 0) payload.put("duration_ms", duration);
            return transport.request(id, session, "device_finder", requestPrefix + sequence.incrementAndGet(), payload);
        } catch (Exception failure) { return null; }
    }

    private boolean matches(String id, String session, JSONObject reply) {
        JSONObject p = projection;
        return !closed && p != null && supports(p) && id.equals(p.optString("id")) && !session.isEmpty()
                && session.equals(p.optString("session_id")) && reply != null && "ok".equals(reply.optString("status"))
                && session.equals(reply.optString("session_id")) && data(reply) != null;
    }

    private static JSONObject data(JSONObject reply) {
        JSONObject result = reply == null ? null : reply.optJSONObject("result");
        return result != null && "device_finder".equals(result.optString("operation")) ? result.optJSONObject("data") : null;
    }

    private static boolean supports(JSONObject p) {
        JSONArray operations = p == null ? null : p.optJSONArray("operations");
        if (operations != null) for (int i = 0; i < operations.length(); i++)
            if ("device_finder".equals(operations.optString(i))) return true;
        return false;
    }

    private static void deliver(Context context, String id, int method, int status) {
        context.sendBroadcast(new Intent("com.miui.circulate.channel.action.RECEIVE_SHARE_CHANNEL")
                .setClassName("com.milink.service", "com.miui.circulate.ringfind.runtime.sharechannel.RequestReceiver")
                .addCategory("ringfind")
                .putExtra("com.miui.circulate.channel.extra.EXTRA_SHARE_CHANNEL_DEVICE_ID", id)
                .putExtra("com.miui.circulate.channel.extra.EXTRA_SHARE_CHANNEL_ID", -1)
                .putExtra("com.miui.circulate.channel.extra.EXTRA_SHARE_CHANNEL_DATA", XiaomiFinderCodec.response(method, status)));
    }

    @Override public synchronized void close() {
        JSONObject selected = projection;
        String session = monitoringSession;
        closed = true;
        update(null);
        if (selected != null && session != null) {
            String id = selected.optString("id");
            try { monitorWorker.execute(() -> request(id, session, "stop", 0)); }
            catch (java.util.concurrent.RejectedExecutionException ignored) { /* remote bounded timeout still applies */ }
        }
        monitorWorker.shutdown();
    }
}
