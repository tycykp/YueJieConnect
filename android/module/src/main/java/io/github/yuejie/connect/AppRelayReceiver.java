package io.github.yuejie.connect;

import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** One instance per authenticated session in the OEM executor. Never share across peers. */
final class AppRelayReceiver implements AutoCloseable {
    private final AppRelayTasks tasks;
    private final java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "yj-relay-expiry"); t.setDaemon(true); return t;
    });
    AppRelayReceiver(AppRelayTasks.Endpoint endpoint, BooleanSupplier authorized) {
        tasks = new AppRelayTasks(endpoint, authorized);
        timer.scheduleWithFixedDelay(tasks::expire, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }
    JSONObject execute(JSONObject payload) throws Exception {
        AppRelayCodec.validateAction(payload);
        String action = payload.getString("action");
        if ("prepare".equals(action) && !payload.getString("request_id").equals(payload.getJSONObject("handoff").getString("request_id")))
            throw new IllegalArgumentException("request_conflict");
        AppRelayTasks.Result result = switch (action) {
            case "prepare" -> tasks.prepare(AppRelayCodec.decode(payload.getJSONObject("handoff")));
            case "launch" -> tasks.launch(payload.getString("request_id"));
            case "status" -> tasks.status(payload.getString("request_id"));
            case "cancel" -> tasks.cancel(payload.getString("request_id"));
            default -> throw new IllegalArgumentException("invalid_relay_action");
        };
        return AppRelayCodec.encode(result);
    }
    @Override public void close() { timer.shutdownNow(); tasks.close(); }
}
