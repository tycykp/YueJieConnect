package io.github.qiangjian.connect;

import java.util.function.Consumer;
import org.json.JSONObject;

/** No new RPC transport; uses the shared authenticated/cancellable bridge and verifies its envelope. */
final class MediaControlBridgeTransport implements MediaControlNativeRoute.Transport {
    private final BridgeClient client;
    MediaControlBridgeTransport(BridgeClient client) { this.client = java.util.Objects.requireNonNull(client); }
    @Override public void submit(MediaControlNativeRoute.Scope scope, String requestId, MediaControlSession.Request request,
            Consumer<MediaControlSession.Result> completion) {
        try {
            if (!current(scope)) { completion.accept(error("stale_session")); return; }
            client.request(scope.projectionId(), scope.sessionId(), MediaControlTasks.OPERATION, requestId,
                    MediaControlWire.payload(scope, request), response -> {
                try {
                    if (!current(scope)) { completion.accept(error("stale_session")); return; }
                    if (response == null || !scope.sessionId().equals(response.optString("session_id"))
                            || !requestId.equals(response.optString("request_id"))) throw new IllegalArgumentException();
                    if (!"ok".equals(response.optString("status"))) {
                        String code = response.optString("code");
                        completion.accept(error(code.matches("[a-z_]{1,64}") ? code : "remote_error")); return;
                    }
                    JSONObject result = response.getJSONObject("result");
                    if (!MediaControlTasks.OPERATION.equals(result.getString("operation"))) throw new IllegalArgumentException();
                    completion.accept(MediaControlWire.result(result.getJSONObject("data")));
                } catch (Exception invalid) { completion.accept(error("invalid_result")); }
            });
        } catch (Exception invalid) { completion.accept(error("invalid_media_request")); }
    }
    private boolean current(MediaControlNativeRoute.Scope scope) {
        JSONObject selected = client.projection();
        return selected != null && scope.projectionId().equals(selected.optString("id"))
                && scope.sessionId().equals(selected.optString("session_id"));
    }
    @Override public void cancel(MediaControlNativeRoute.Scope scope, String requestId) {
        client.cancel(scope.sessionId(), requestId, ignored -> { });
    }
    private static MediaControlSession.Result error(String code) { return new MediaControlSession.Result(code, false, null); }
}
