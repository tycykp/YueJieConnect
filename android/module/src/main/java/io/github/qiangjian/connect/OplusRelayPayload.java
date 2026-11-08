package io.github.qiangjian.connect;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact seven-byte data-relay envelope. The JSON is data, never an Intent or a URI grant. */
record OplusRelayPayload(int appId, int type, String sourcePackage, String json) {
    static final String PKG = "Relay_Base_Key_Pkg_Name", TYPE = "Relay_Base_Key_Payload_Type", ID = "Relay_Base_Key_AppId";
    static final String DEMO = "com.example.datarelaydemo", DEMO01 = "com.example.datarelaydemo01";
    OplusRelayPayload {
        AppRelayRequest.packageName(sourcePackage);
        if (appId < 0 || appId > 65535 || !List.of(0, 3, 4, 5, 6, 7).contains(type))
            throw new IllegalArgumentException("not_application_handoff");
        try {
            JSONArray values = profiles(json);
            if (!sourcePackage.equals(value(values, PKG)) || !Integer.toString(type).equals(value(values, TYPE))
                    || !Integer.toString(appId).equals(value(values, ID)))
                throw new IllegalArgumentException("relay_header_mismatch");
            if ((DEMO.equals(sourcePackage) && appId != 200) || (DEMO01.equals(sourcePackage) && appId != 201))
                throw new IllegalArgumentException("relay_header_mismatch");
        } catch (org.json.JSONException invalid) { throw new IllegalArgumentException("invalid_relay_profile"); }
    }
    static OplusRelayPayload decode(byte[] wire) throws Exception {
        if (wire == null || wire.length < 9 || wire.length > 16384) throw new IllegalArgumentException("invalid_relay_payload");
        for (int i = 3; i < 7; i++) if (wire[i] != 0) throw new IllegalArgumentException("invalid_relay_header");
        String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(wire, 7, wire.length - 7)).toString();
        JSONArray values = profiles(json);
        return new OplusRelayPayload(((wire[0] & 255) << 8) | (wire[1] & 255), wire[2] & 255, value(values, PKG), json);
    }
    static OplusRelayPayload richText(String json, String source) {
        try {
            JSONArray values = profiles(json);
            String appId = value(values, ID), type = value(values, TYPE);
            if (!appId.matches("[0-9]{1,5}") || !type.matches("[0-9]")) throw new IllegalArgumentException("invalid_relay_profile");
            OplusRelayPayload result = new OplusRelayPayload(Integer.parseInt(appId), Integer.parseInt(type), source, json);
            if (!result.richText()) throw new IllegalArgumentException("invalid_rich_text_target");
            return result;
        } catch (org.json.JSONException invalid) { throw new IllegalArgumentException("invalid_relay_profile"); }
    }
    boolean richText() { return DEMO.equals(sourcePackage) || DEMO01.equals(sourcePackage); }
    String activity() {
        if (!richText()) throw new IllegalArgumentException("invalid_rich_text_target");
        return "com.example.datarelaydemo.ui.EditActivity" + (DEMO01.equals(sourcePackage) ? "01" : "");
    }
    JSONArray profiles() throws org.json.JSONException { return profiles(json); }
    private static JSONArray profiles(String json) throws org.json.JSONException {
        if (json == null || json.indexOf('\0') >= 0 || json.getBytes(StandardCharsets.UTF_8).length > 16377)
            throw new IllegalArgumentException("invalid_relay_payload");
        JSONArray values = new JSONArray(json);
        if (values.length() < 3 || values.length() > 48) throw new IllegalArgumentException("invalid_relay_profile");
        HashSet<String> keys = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject entry = values.getJSONObject(i);
            if (entry.length() != 2 || !(entry.get("key") instanceof String key) || !(entry.get("value") instanceof String value)
                    || key.isEmpty() || key.length() > 128 || value.length() > 8192 || value.indexOf('\0') >= 0
                    || !keys.add(key)) throw new IllegalArgumentException("invalid_relay_profile");
        }
        return values;
    }
    private static String value(JSONArray values, String key) throws org.json.JSONException {
        for (int i = 0; i < values.length(); i++) if (key.equals(values.getJSONObject(i).getString("key")))
            return values.getJSONObject(i).getString("value");
        throw new IllegalArgumentException("missing_relay_profile");
    }
    @Override public String toString() { return "OplusRelayPayload[type=" + type + "]"; }
}
