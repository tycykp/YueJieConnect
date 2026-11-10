package io.github.yuejie.connect;

import java.util.Set;
import org.json.JSONObject;

/** Bounded media-only JSON. Shared owner must mirror these rules in the public typed wire. */
final class MediaControlWire {
    record Payload(long streamId, int screenId, JSONObject control, MediaControlSession.Request request) { }
    private static final Set<String> STATE_FIELDS = Set.of("media_token", "playback", "position_ms", "actions",
            "volume", "min_volume", "max_volume", "interactive", "power_save");
    static JSONObject payload(MediaControlNativeRoute.Scope scope, MediaControlSession.Request request) throws Exception {
        return new JSONObject().put("cast_stream_id", scope.streamId()).put("cast_screen_id", scope.screenId())
                .put("control", request(request));
    }
    static Payload payload(JSONObject raw) throws Exception {
        fields(raw, Set.of("cast_stream_id", "cast_screen_id", "control"), 2048);
        if (raw.length() != 3) throw new IllegalArgumentException("invalid_payload");
        long stream = integer(raw, "cast_stream_id");
        int screen = Math.toIntExact(integer(raw, "cast_screen_id"));
        JSONObject control = raw.getJSONObject("control");
        var request = MediaControlEndpoint.decode(control);
        // Zero identifies explicitly granted device volume, never a fabricated cast lease.
        if (stream < 0 || screen < 0 || stream == 0 && (screen != 0
                || request.action() != MediaControlSession.Action.VOLUME_QUERY
                && request.action() != MediaControlSession.Action.SET_VOLUME))
            throw new IllegalArgumentException("invalid_media_scope");
        return new Payload(stream, screen, control, request);
    }
    static JSONObject request(MediaControlSession.Request request) throws Exception {
        JSONObject out = new JSONObject().put("action", request.action().name());
        if (MediaControlSession.isMedia(request.action())) {
            out.put("package", request.packageName());
            if (request.action() != MediaControlSession.Action.QUERY) out.put("media_token", request.token());
        }
        if (request.action() == MediaControlSession.Action.SEEK || request.action() == MediaControlSession.Action.SET_VOLUME
                || request.action() == MediaControlSession.Action.RESTORE_VOLUME) out.put("value", request.value());
        if (request.expectedVolume() >= 0) out.put("expected_volume", request.expectedVolume());
        MediaControlEndpoint.decode(out);
        return out;
    }
    static boolean confirmed(JSONObject response) {
        try { return result(response).confirmed(); } catch (Exception invalid) { return false; }
    }
    static MediaControlSession.Result result(JSONObject json) throws Exception {
        fields(json, Set.of("status", "code", "dispatched", "state"), 2048);
        if (!(json.get("status") instanceof String) || !(json.get("code") instanceof String)
                || !(json.get("dispatched") instanceof Boolean)) throw new IllegalArgumentException("invalid_result");
        String code = json.getString("code"), status = json.getString("status");
        if (!code.matches("[a-z_]{1,64}") || !("ok".equals(status) || "error".equals(status))
                || "ok".equals(status) != "ok".equals(code)) throw new IllegalArgumentException("invalid_result");
        MediaControlSession.State state = null;
        if (json.has("state")) {
            JSONObject raw = json.getJSONObject("state");
            fields(raw, STATE_FIELDS, 1800);
            if (raw.length() != STATE_FIELDS.size() || !(raw.get("media_token") instanceof String)
                    || !(raw.get("interactive") instanceof Boolean) || !(raw.get("power_save") instanceof Boolean))
                throw new IllegalArgumentException("invalid_result");
            String token = raw.getString("media_token");
            if (!token.isEmpty() && !token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new IllegalArgumentException("invalid_result");
            int playback = Math.toIntExact(integer(raw, "playback"));
            long position = integer(raw, "position_ms"), actions = integer(raw, "actions");
            int volume = Math.toIntExact(integer(raw, "volume")), min = Math.toIntExact(integer(raw, "min_volume")),
                    max = Math.toIntExact(integer(raw, "max_volume"));
            if (playback < 0 || playback > 11 || position < -1 || actions < 0 || min < 0 || max < min
                    || max > 10_000 || volume < min || volume > max) throw new IllegalArgumentException("invalid_result");
            state = new MediaControlSession.State(token, playback, position, actions, volume, min, max,
                    raw.getBoolean("interactive"), raw.getBoolean("power_save"));
        }
        if ("ok".equals(status) && state == null) throw new IllegalArgumentException("invalid_result");
        return new MediaControlSession.Result(code, json.getBoolean("dispatched"), state);
    }
    static void fields(JSONObject json, Set<String> allowed, int limit) {
        if (json == null || json.toString().length() > limit) throw new IllegalArgumentException("invalid_payload");
        java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) throw new IllegalArgumentException("invalid_field");
    }
    static long integer(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("invalid_field");
        return ((Number) value).longValue();
    }
}
