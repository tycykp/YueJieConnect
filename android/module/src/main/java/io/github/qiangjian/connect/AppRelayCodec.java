package io.github.qiangjian.connect;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class AppRelayCodec {
    static void validateAction(JSONObject json) throws Exception {
        String action = string(json, "action", null), id = string(json, "request_id", null);
        if (id == null || action == null || !id.matches("[A-Za-z0-9_-]{1,96}") || !java.util.Set.of("prepare", "launch", "status", "cancel", "catalog", "sync").contains(action))
            throw new IllegalArgumentException("invalid_relay_action");
        if ("sync".equals(action)) {
            if (json.length() != (json.has("native_owner_token") ? 4 : 3)) throw new IllegalArgumentException("unexpected_field");
            AppRelaySync.validate(id, string(json, "source_package", null));
            String token = string(json, "native_owner_token", ""); AppRelaySync.validateOwnerToken(token);
            if (json.has("native_owner_token") && token.isEmpty()) throw new IllegalArgumentException("invalid_native_owner_token");
        } else if (json.length() != ("prepare".equals(action) || "catalog".equals(action) ? 3 : 2)) throw new IllegalArgumentException("unexpected_field");
        if ("catalog".equals(action)) AppRelayCatalogCodec.decode(json.getJSONObject("catalog"));
        if ("prepare".equals(action)) {
            AppRelayRequest request = decode(json.getJSONObject("handoff"));
            if (!id.equals(request.requestId())) throw new IllegalArgumentException("request_conflict");
        }
    }
    static AppRelayRequest decode(JSONObject json) throws JSONException {
        java.util.Set<String> fields = java.util.Set.of("version", "request_id", "source_package", "kind", "targets", "uri", "signer_sha256",
                "file_token", "mime", "progress_type", "progress", "progress_text", "native_payload");
        var names = json.keys();
        while (names.hasNext()) if (!fields.contains(names.next())) throw new IllegalArgumentException("unexpected_field");
        if (json.toString().getBytes(StandardCharsets.UTF_8).length > 6144 || integer(json, "version", -1) != 1)
            throw new IllegalArgumentException("invalid_payload");
        JSONArray list = json.getJSONArray("targets");
        if (list.length() > 8) throw new IllegalArgumentException("invalid_targets");
        ArrayList<String> targets = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            if (!(list.get(i) instanceof String value)) throw new IllegalArgumentException("invalid_targets");
            targets.add(value);
        }
        AppRelayRequest.Kind kind;
        try { kind = AppRelayRequest.Kind.valueOf(string(json, "kind", "")); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("invalid_kind"); }
        return new AppRelayRequest(string(json, "request_id", null), string(json, "source_package", null), kind, targets,
                string(json, "uri", ""), string(json, "signer_sha256", ""), string(json, "file_token", ""),
                string(json, "mime", ""), integer(json, "progress_type", 0), integer(json, "progress", 0), string(json, "progress_text", ""),
                string(json, "native_payload", ""));
    }
    private static String string(JSONObject json, String key, String fallback) throws JSONException {
        if (!json.has(key)) return fallback;
        if (!(json.get(key) instanceof String value)) throw new IllegalArgumentException("invalid_field_type");
        return value;
    }
    private static int integer(JSONObject json, String key, int fallback) throws JSONException {
        if (!json.has(key)) return fallback;
        Object value = json.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("invalid_field_type");
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new IllegalArgumentException("invalid_field_type");
        return (int) number;
    }
    static JSONObject encode(AppRelayRequest r) throws JSONException {
        JSONObject json = new JSONObject().put("version", 1).put("request_id", r.requestId()).put("source_package", r.sourcePackage())
                .put("kind", r.kind().name()).put("targets", new JSONArray(r.targets())).put("uri", r.uri())
                .put("signer_sha256", r.signerSha256()).put("file_token", r.fileToken()).put("mime", r.mime())
                .put("progress_type", r.progressType()).put("progress", r.progress()).put("progress_text", r.progressText());
        if (!r.nativePayload().isEmpty()) json.put("native_payload", r.nativePayload());
        if (json.toString().getBytes(StandardCharsets.UTF_8).length > 6144) throw new IllegalArgumentException("payload_too_large");
        return json;
    }
    static JSONObject encode(AppRelayTasks.Result r) throws JSONException {
        return new JSONObject().put("version", 1).put("request_id", r.requestId()).put("state", r.state().name())
                .put("code", r.code()).put("target_package", r.targetPackage()).put("task_id", r.taskId());
    }
    static AppRelayTasks.Result decodeResult(JSONObject json) throws JSONException {
        if (json == null || json.length() != 6 || integer(json, "version", -1) != 1) throw new IllegalArgumentException("invalid_remote_receipt");
        String id = string(json, "request_id", ""), code = string(json, "code", ""), target = string(json, "target_package", "");
        if (!id.matches("[A-Za-z0-9_-]{1,96}") || !code.matches("[a-z_]{1,64}")) throw new IllegalArgumentException("invalid_remote_receipt");
        if (!target.isEmpty()) AppRelayRequest.packageName(target);
        AppRelayTasks.State state = AppRelayTasks.State.valueOf(string(json, "state", ""));
        int task = integer(json, "task_id", -2);
        if (task < -1 || (state == AppRelayTasks.State.COMPLETED ? task < 0 : task != -1)
                || ((state == AppRelayTasks.State.PREPARED || state == AppRelayTasks.State.LAUNCHING || state == AppRelayTasks.State.COMPLETED) && target.isEmpty()))
            throw new IllegalArgumentException("invalid_remote_receipt");
        return new AppRelayTasks.Result(id, state, code, target, task);
    }
    private AppRelayCodec() {}
}
