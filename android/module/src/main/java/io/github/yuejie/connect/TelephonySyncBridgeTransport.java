package io.github.yuejie.connect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.json.JSONObject;
import static io.github.yuejie.connect.TelephonySyncController.*;
import static io.github.yuejie.connect.TelephonySyncWire.*;

/** Uses the existing authenticated/cancellable control bridge; preserves the source's exact session. */
final class TelephonySyncBridgeTransport implements TelephonySyncRelay.Sender {
    private final BridgeClient client;
    private final Session session;
    TelephonySyncBridgeTransport(BridgeClient client, Session session) { this.client = client; this.session = session; }
    CompletableFuture<TelephonySyncRelay.Receipt> state(Snapshot snapshot) {
        String request = "call_state_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try { return submit(CALL_STATE, request, TelephonySyncWire.state(snapshot), raw -> stateReceipt(snapshot, raw)); }
        catch (RuntimeException invalid) { return CompletableFuture.failedFuture(invalid); }
    }
    @Override public CompletableFuture<Result> send(Command command, long remainingMs) {
        if (!session.equals(command.session()) || remainingMs <= 0) return CompletableFuture.failedFuture(new Failure("session_invalid"));
        return submit(CALL_CONTROL, command.request(), control(command), raw -> controlReceipt(command, raw));
    }
    private <T> CompletableFuture<T> submit(String operation, String request, Map<String, Object> payload, Function<Map<String, Object>, T> decode) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            if (!current()) throw new Failure("session_invalid");
            client.request(session.peer(), session.id(), operation, request, new JSONObject(payload), response -> {
                try {
                    if (!current() || response == null || !session.id().equals(response.optString("session_id"))
                            || !request.equals(response.optString("request_id"))) throw new Failure("session_invalid");
                    if (!"ok".equals(response.optString("status"))) throw new Failure(response.optString("code"));
                    JSONObject receipt = response.getJSONObject("result");
                    if (!operation.equals(receipt.getString("operation"))) throw new Failure("invalid_result");
                    result.complete(decode.apply(map(receipt.getJSONObject("data"))));
                } catch (Exception invalid) { result.completeExceptionally(invalid instanceof Failure ? invalid : new Failure("invalid_result")); }
            });
        } catch (RuntimeException invalid) { result.completeExceptionally(invalid); }
        return result;
    }
    private boolean current() {
        JSONObject projection = client.projection();
        return projection != null && session.peer().equals(projection.optString("id")) && session.id().equals(projection.optString("session_id"));
    }
    static Map<String, Object> map(JSONObject object) throws Exception {
        if (object == null || object.length() > 4) throw new Failure("invalid_payload");
        Map<String, Object> result = new LinkedHashMap<>(); var keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next(); Object value = object.get(key);
            if (!(value instanceof String) && !(value instanceof Integer) && !(value instanceof Long) && !(value instanceof Boolean))
                throw new Failure("invalid_payload");
            result.put(key, value);
        }
        return result;
    }
}
