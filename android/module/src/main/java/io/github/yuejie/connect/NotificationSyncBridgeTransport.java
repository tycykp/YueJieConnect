package io.github.yuejie.connect;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Typed notification fragments stay below Binder's unchanged 8 KiB JSON budget. */
final class NotificationSyncBridgeTransport implements NotificationSyncRuntime.Transport {
    interface Channel {
        JSONObject projection();
        void request(String peer, String session, String operation, String request, JSONObject payload, Consumer<JSONObject> completion);
        void cancel(String session, String request);
    }
    private record Key(String session, String request) {}
    private static final class Outbound {
        final String peer, session, request, operation;
        final NotificationSyncCodec.Frame frame;
        final List<JSONObject> parts;
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        volatile String inFlight;
        Outbound(String peer, String session, String request, String operation, NotificationSyncCodec.Frame frame, List<JSONObject> parts) {
            this.peer = peer; this.session = session; this.request = request; this.operation = operation; this.frame = frame; this.parts = parts;
        }
    }
    private final Channel client;
    private final BooleanSupplier allowed;
    private final Map<Key, Outbound> outgoing = new ConcurrentHashMap<>();
    NotificationSyncBridgeTransport(BridgeClient client, BooleanSupplier allowed) {
        this(new Channel() {
            @Override public JSONObject projection() { return client.projection(); }
            @Override public void request(String peer, String session, String operation, String request, JSONObject payload, Consumer<JSONObject> completion) {
                client.request(peer, session, operation, request, payload, completion);
            }
            @Override public void cancel(String session, String request) { client.cancel(session, request, ignored -> { }); }
        }, allowed);
    }
    NotificationSyncBridgeTransport(Channel client, BooleanSupplier allowed) { this.client = client; this.allowed = allowed; }
    static boolean supports(JSONObject projection, String operation) {
        if (projection == null || !projection.optString("session_id").matches("[0-9a-f]{64}")) return false;
        JSONArray operations = projection.optJSONArray("operations");
        if (operations != null) for (int i = 0; i < operations.length(); i++) if (operation.equals(operations.optString(i))) return true;
        return false;
    }
    private boolean current(String peer, String session, String operation) {
        JSONObject p = client.projection();
        return allowed.getAsBoolean() && supports(p, operation) && peer.equals(p.optString("id")) && session.equals(p.optString("session_id"));
    }
    @Override public synchronized CompletableFuture<byte[]> send(String peer, String session, String request, String operation, byte[] data) {
        try {
            if (!current(peer, session, operation)) throw new IllegalStateException("notification_peer_unavailable");
            NotificationSyncCodec.Frame frame = NotificationSyncCodec.decode(data);
            if (!NotificationSyncCodec.operation(frame).equals(operation)) throw new IllegalArgumentException("notification_invalid_operation");
            if (outgoing.size() >= NotificationSyncFragments.MAX_ACTIVE) throw new IllegalStateException("notification_capacity");
            Key key = new Key(session, request);
            Outbound out = new Outbound(peer, session, request, operation, frame, NotificationSyncFragments.split(request, data));
            if (outgoing.putIfAbsent(key, out) != null) throw new IllegalStateException("notification_request_conflict");
            out.result.whenComplete((bytes, error) -> {
                outgoing.remove(key, out);
                if (error != null) abort(out);
            });
            out.result.orTimeout(NotificationSyncFragments.LIFETIME_MS, TimeUnit.MILLISECONDS);
            part(out, 0); return out.result;
        } catch (JSONException | RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    private void part(Outbound out, int index) {
        if (out.result.isDone()) return;
        try {
            if (!current(out.peer, out.session, out.operation)) throw new IllegalStateException("notification_stale_session");
            out.inFlight = out.request + ":part" + index;
            String wireRequest = out.inFlight;
            client.request(out.peer, out.session, out.operation, wireRequest, out.parts.get(index), response -> {
                if (out.result.isDone()) return;
                try {
                    if (!current(out.peer, out.session, out.operation)) throw new IllegalStateException("notification_stale_session");
                    JSONObject data = result(response, out.session, wireRequest, out.operation);
                    byte[] receipt = NotificationSyncFragments.checkAcknowledgement(data, NotificationSyncFragments.parse(out.parts.get(index)), out.frame);
                    if (index == out.parts.size() - 1) out.result.complete(receipt);
                    else part(out, index + 1);
                } catch (JSONException | RuntimeException failure) { out.result.completeExceptionally(new IllegalStateException("notification_remote_failed")); }
            });
        } catch (RuntimeException failure) { out.result.completeExceptionally(failure); }
    }
    private void abort(Outbound out) {
        if (out.inFlight != null) client.cancel(out.session, out.inFlight);
        try {
            if (current(out.peer, out.session, out.operation)) client.request(out.peer, out.session, out.operation, out.request + ":abort",
                    NotificationSyncFragments.abort(out.request, NotificationSyncFragments.parse(out.parts.get(0)).sha256()), ignored -> { });
        } catch (JSONException | RuntimeException ignored) { /* Session fencing and the fixed ingress expiry still apply. */ }
    }
    @Override public void cancel(String peer, String session, String request) {
        Outbound out = outgoing.get(new Key(session, request));
        if (out != null && out.peer.equals(peer)) out.result.completeExceptionally(new IllegalStateException("notification_cancelled"));
    }
    private static JSONObject result(JSONObject response, String session, String request, String operation) throws JSONException {
        if (response == null || !"ok".equals(response.opt("status")) || !session.equals(response.opt("session_id"))
                || !request.equals(response.opt("request_id"))) throw new IllegalArgumentException("notification_invalid_receipt");
        JSONObject result = response.getJSONObject("result");
        if (!operation.equals(result.opt("operation"))) throw new IllegalArgumentException("notification_invalid_receipt");
        return result.getJSONObject("data");
    }
    static byte[] receipt(JSONObject response, String session, String request, NotificationSyncCodec.Frame frame) throws JSONException {
        byte[] receipt = bytes(result(response, session, request, NotificationSyncCodec.operation(frame)));
        NotificationSyncCodec.checkReceipt(frame, receipt); return receipt;
    }
    static JSONObject payload(byte[] bytes) throws JSONException {
        if (bytes == null || bytes.length > NotificationSyncCodec.MAX_BYTES) throw new IllegalArgumentException("notification_invalid_payload");
        return new JSONObject().put("data", Base64.getEncoder().encodeToString(bytes));
    }
    static byte[] bytes(JSONObject payload) throws JSONException {
        if (payload == null || payload.length() != 1 || !(payload.opt("data") instanceof String)) throw new IllegalArgumentException("notification_invalid_payload");
        String value = payload.getString("data");
        if (value.length() > 13656) throw new IllegalArgumentException("notification_invalid_payload");
        byte[] decoded = Base64.getDecoder().decode(value);
        if (decoded.length > NotificationSyncCodec.MAX_BYTES || !Base64.getEncoder().encodeToString(decoded).equals(value))
            throw new IllegalArgumentException("notification_invalid_payload");
        return decoded;
    }
}
