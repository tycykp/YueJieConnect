package io.github.yuejie.connect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only the selected, authenticated bridge phone/pad can use the native local sender. */
final class OplusFinderSender implements AutoCloseable {
    interface Transport {
        void request(String id, String session, String operation, String request, JSONObject payload, Consumer<JSONObject> reply);
        void cancel(String session, String request);
    }
    record Outcome(boolean success, boolean ringing, String code) {}
    private static final Outcome STALE = new Outcome(false, false, "stale_session");
    private final Transport transport;
    private final String prefix = "oplus-finder-" + UUID.randomUUID() + "-";
    private final Map<String, Receipt> receipts = new LinkedHashMap<>();
    private JSONObject projection;
    private long sequence;
    private boolean closed, ringing;
    private static final class Receipt {
        final String session, id, request, action;
        final List<Consumer<Outcome>> callbacks = new ArrayList<>();
        Outcome outcome;
        Receipt(String session, String id, String request, String action) {
            this.session = session; this.id = id; this.request = request; this.action = action;
        }
    }
    OplusFinderSender(Transport transport) { this.transport = transport; }

    synchronized void update(JSONObject selected) {
        JSONObject next;
        try { next = selected == null ? null : new JSONObject(selected.toString()); }
        catch (org.json.JSONException invalid) { next = null; }
        boolean changedPeer = !samePeer(projection, next);
        boolean revoked = supports(projection) && !supports(next);
        projection = next;
        if (changedPeer || revoked) retire(changedPeer);
    }

    static boolean bridgeId(String nativeId) { return nativeId != null && nativeId.matches("yj:[0-9a-f]{64}"); }

    /** False leaves ordinary cloud/earphone/watch requests completely with the OEM. */
    synchronized boolean send(String nativeId, String nativeType, String messageId, String nativeAction, Consumer<Outcome> completion) {
        if (!bridgeId(nativeId)) return false;
        JSONObject selected = projection;
        if (closed || selected == null || !nativeId.equals(selected.optString("id"))) { deliver(completion, STALE); return true; }
        if (!supports(selected) || peerType(selected) == null || !Objects.equals(nativeType, peerType(selected))) {
            deliver(completion, new Outcome(false, false, "permission_denied")); return true;
        }
        String action = switch (nativeAction == null ? "" : nativeAction) {
            case "RING" -> "start";
            case "STOP_RING" -> "stop";
            default -> null;
        };
        if (action == null) { deliver(completion, new Outcome(false, false, "invalid_action")); return true; }
        if (messageId == null || messageId.isEmpty() || messageId.length() > 128) {
            deliver(completion, new Outcome(false, false, "invalid_request_id")); return true;
        }
        Receipt previous = receipts.get(messageId);
        if (previous != null) {
            if (!previous.action.equals(action)) deliver(completion, new Outcome(false, false, "request_conflict"));
            else if (previous.outcome != null) deliver(completion, previous.outcome);
            else if (previous.callbacks.size() < 4) previous.callbacks.add(completion);
            else deliver(completion, new Outcome(false, false, "finder_busy"));
            return true;
        }
        // Reserve the last receipt for a real stop even after start capacity is exhausted.
        if (receipts.size() >= ("stop".equals(action) ? 256 : 255)) {
            deliver(completion, new Outcome(false, false, "finder_busy")); return true;
        }
        long pending = receipts.values().stream().filter(item -> item.outcome == null).count();
        if (pending >= 16 && !"stop".equals(action) || pending >= 17) {
            deliver(completion, new Outcome(false, false, "finder_busy")); return true;
        }
        Receipt receipt = new Receipt(selected.optString("session_id"), nativeId, prefix + ++sequence, action);
        if (receipt.session.isEmpty()) { deliver(completion, STALE); return true; }
        receipt.callbacks.add(completion);
        receipts.put(messageId, receipt);
        try {
            JSONObject payload = new JSONObject().put("action", action);
            if ("start".equals(action)) payload.put("duration_ms", 10_000);
            transport.request(receipt.id, receipt.session, "device_finder", receipt.request, payload, reply -> finish(receipt, reply));
        } catch (RuntimeException | org.json.JSONException failure) { finish(receipt, null); }
        return true;
    }

    private synchronized void finish(Receipt receipt, JSONObject reply) {
        if (receipt.outcome != null) return;
        Outcome outcome;
        if (closed || projection == null || !receipt.id.equals(projection.optString("id"))
                || !receipt.session.equals(projection.optString("session_id")) || !supports(projection)) outcome = STALE;
        else {
            JSONObject result = reply == null ? null : reply.optJSONObject("result");
            JSONObject data = result == null ? null : result.optJSONObject("data");
            boolean acknowledged = reply != null && "ok".equals(reply.optString("status"))
                    && receipt.session.equals(reply.optString("session_id")) && receipt.request.equals(reply.optString("request_id"))
                    && result != null && "device_finder".equals(result.optString("operation")) && data != null
                    && data.opt("ringing") instanceof Boolean;
            boolean actual = acknowledged && data.optBoolean("ringing");
            Object remaining = data == null ? null : data.opt("remaining_ms");
            boolean duration = remaining instanceof Integer || remaining instanceof Long;
            long left = duration ? ((Number) remaining).longValue() : -1;
            boolean valid = acknowledged && duration && ("start".equals(receipt.action) ? actual && left > 0 && left <= 30_000 : !actual && left == 0);
            if (valid) { ringing = actual; outcome = new Outcome(true, actual, ""); }
            else outcome = new Outcome(false, false, "finder_execution_failed");
        }
        receipt.outcome = outcome;
        List<Consumer<Outcome>> callbacks = List.copyOf(receipt.callbacks);
        receipt.callbacks.clear();
        for (Consumer<Outcome> callback : callbacks) deliver(callback, outcome);
    }

    private static void deliver(Consumer<Outcome> callback, Outcome outcome) {
        try { callback.accept(outcome); } catch (RuntimeException ignored) { /* Do not prevent another OEM completion. */ }
    }
    private static String peerType(JSONObject selected) {
        JSONObject real = selected.optJSONObject("real");
        String type = real == null ? "" : real.optString("kind");
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "phone" -> "PHONE";
            case "pad", "tablet" -> "PAD";
            default -> null;
        };
    }
    private static boolean supports(JSONObject selected) {
        JSONArray operations = selected == null ? null : selected.optJSONArray("operations");
        if (operations != null) for (int i = 0; i < operations.length(); i++)
            if ("device_finder".equals(operations.optString(i))) return true;
        return false;
    }
    private static boolean samePeer(JSONObject before, JSONObject after) {
        return before != null && after != null && before.optString("id").equals(after.optString("id"))
                && before.optString("session_id").equals(after.optString("session_id"))
                && Objects.equals(peerType(before), peerType(after));
    }
    private void retire(boolean clearReceipts) {
        List<Receipt> retired = List.copyOf(receipts.values());
        if (clearReceipts) receipts.clear();
        ringing = false;
        for (Receipt item : retired) {
            boolean pending = item.outcome == null;
            item.outcome = STALE;
            if (pending) try { transport.cancel(item.session, item.request); } catch (RuntimeException ignored) { }
            List<Consumer<Outcome>> callbacks = List.copyOf(item.callbacks);
            item.callbacks.clear();
            for (Consumer<Outcome> callback : callbacks) deliver(callback, STALE);
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        JSONObject selected = projection;
        // The receiver may already have started while its acknowledgement is still in flight.
        // Cancellation alone cannot stop a task that has just completed in the remote mailbox.
        boolean uncertainStart = receipts.values().stream().anyMatch(item -> "start".equals(item.action) && item.outcome == null);
        boolean stop = selected != null && (ringing || uncertainStart);
        retire(true);
        projection = null;
        if (stop) try {
            transport.request(selected.optString("id"), selected.optString("session_id"), "device_finder", prefix + ++sequence,
                    new JSONObject().put("action", "stop"), ignored -> { });
        } catch (RuntimeException | org.json.JSONException ignored) { /* Receiver's bounded timeout remains. */ }
    }
}
