package io.github.yuejie.connect;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class AppRelayCatalogCodec {
    record Delivery(long revision, AppRelayCatalog.Update update) {}
    static Delivery decode(JSONObject json) throws Exception {
        fields(json, Set.of("revision", "clear_all", "candidates"));
        Object number = json.get("revision");
        if (!(number instanceof Integer || number instanceof Long) || ((Number) number).longValue() < 1
                || !(json.get("clear_all") instanceof Boolean clear)) throw new IllegalArgumentException("invalid_relay_catalog");
        JSONArray entries = json.getJSONArray("candidates");
        if (entries.length() > 16) throw new IllegalArgumentException("invalid_relay_catalog");
        ArrayList<AppRelayCatalog.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            fields(entry, Set.of("package", "label", "kind", "visible", "data_relay", "destination", "activity"));
            Object destination = entry.get("destination");
            if (!(entry.get("visible") instanceof Boolean visible) || !(entry.get("data_relay") instanceof Boolean data)
                    || !(destination instanceof Integer || destination instanceof Long) || ((Number) destination).longValue() < 0
                    || ((Number) destination).longValue() > 255) throw new IllegalArgumentException("invalid_relay_catalog");
            candidates.add(new AppRelayCatalog.Candidate(string(entry, "package"), string(entry, "label"),
                    AppRelayCatalog.Kind.valueOf(string(entry, "kind")), visible, data, ((Number) destination).intValue(), string(entry, "activity")));
        }
        return new Delivery(((Number) number).longValue(), new AppRelayCatalog.Update(clear, candidates));
    }
    static JSONObject encode(long revision, AppRelayCatalog.Update update) throws Exception {
        JSONArray list = new JSONArray();
        for (AppRelayCatalog.Candidate value : update.candidates()) list.put(new JSONObject().put("package", value.packageName())
                .put("label", value.label()).put("kind", value.kind().name()).put("visible", value.visible())
                .put("data_relay", value.advertisesDataRelay()).put("destination", value.nativeDestinationType()).put("activity", value.activity()));
        JSONObject result = new JSONObject().put("revision", revision).put("clear_all", update.clearAll()).put("candidates", list);
        decode(result); return result;
    }
    private static void fields(JSONObject json, Set<String> expected) {
        if (json.length() != expected.size() || json.toString().getBytes(StandardCharsets.UTF_8).length > 6144)
            throw new IllegalArgumentException("invalid_relay_catalog");
        var keys = json.keys(); while (keys.hasNext()) if (!expected.contains(keys.next())) throw new IllegalArgumentException("unexpected_field");
    }
    private static String string(JSONObject json, String key) throws Exception {
        if (!(json.get(key) instanceof String value)) throw new IllegalArgumentException("invalid_field_type"); return value;
    }
    private AppRelayCatalogCodec() {}
}
