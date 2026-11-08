package io.github.qiangjian.connect;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native type9 DATA confirmation and type8 SDK request. No cast/control passthrough. */
final class OplusRelayControl {
    static String request(String pkg, int appId, String nativeDeviceName) throws Exception {
        AppRelayRequest.packageName(pkg);
        if (appId <= 0 || appId > 65535) throw new IllegalArgumentException("invalid_native_app_id");
        return base(pkg, appId, 8, nativeDeviceName).toString();
    }
    static byte[] confirmation(String nativeDeviceName) throws Exception {
        JSONArray values = base("com.oplus.linker", 503, 9, nativeDeviceName);
        values.put(profile("Relay_Extend_Key_Relay_Type", "1"));
        return wire(503, 9, values.toString());
    }
    static byte[] sdkData(String json, String expectedPackage, int appId) throws Exception {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 16377) throw new IllegalArgumentException("invalid_relay_payload");
        JSONArray input = new JSONArray(json), normalized = new JSONArray();
        if (input.length() < 2 || input.length() > 47) throw new IllegalArgumentException("invalid_relay_profile");
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        int type = -1;
        for (int i = 0; i < input.length(); i++) {
            JSONObject item = input.getJSONObject(i);
            if (item.length() != 2 || !(item.get("key") instanceof String key) || !(item.get("value") instanceof String value) || !keys.add(key))
                throw new IllegalArgumentException("invalid_relay_profile");
            if (OplusRelayPayload.PKG.equals(key) && !expectedPackage.equals(value)) throw new SecurityException("source_package_mismatch");
            if (OplusRelayPayload.TYPE.equals(key)) {
                if (!value.matches("[034567]")) throw new IllegalArgumentException("not_application_handoff");
                type = Integer.parseInt(value);
            }
            if (OplusRelayPayload.ID.equals(key)) {
                if (!Integer.toString(appId).equals(value)) throw new IllegalArgumentException("relay_header_mismatch");
            } else normalized.put(item);
        }
        if (!keys.contains(OplusRelayPayload.PKG) || type < 0) throw new IllegalArgumentException("missing_relay_profile");
        normalized.put(profile(OplusRelayPayload.ID, Integer.toString(appId)));
        byte[] result = wire(appId, type, normalized.toString());
        OplusRelayPayload parsed = OplusRelayPayload.decode(result);
        if (!expectedPackage.equals(parsed.sourcePackage())) throw new SecurityException("source_package_mismatch");
        return result;
    }
    static String sourcePackage(String json) throws Exception {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 16377) throw new IllegalArgumentException("invalid_relay_payload");
        JSONArray input = new JSONArray(json);
        String source = null;
        if (input.length() > 48) throw new IllegalArgumentException("invalid_relay_profile");
        for (int i = 0; i < input.length(); i++) {
            JSONObject item = input.getJSONObject(i);
            if (OplusRelayPayload.PKG.equals(item.get("key"))) {
                if (source != null || !(item.get("value") instanceof String value)) throw new IllegalArgumentException("invalid_relay_profile");
                source = value;
            }
        }
        AppRelayRequest.packageName(source); return source;
    }
    private static JSONArray base(String pkg, int appId, int type, String deviceName) throws Exception {
        return new JSONArray().put(profile(OplusRelayPayload.PKG, pkg)).put(profile(OplusRelayPayload.TYPE, Integer.toString(type)))
                .put(profile(OplusRelayPayload.ID, Integer.toString(appId)))
                .put(profile("Relay_Base_Key_Device_Name", AppRelayCatalog.text(deviceName, 256)));
    }
    private static JSONObject profile(String key, String value) throws Exception { return new JSONObject().put("key", key).put("value", value); }
    private static byte[] wire(int appId, int type, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8), result = new byte[7 + body.length];
        result[0] = (byte)(appId >> 8); result[1] = (byte)appId; result[2] = (byte)type;
        System.arraycopy(body, 0, result, 7, body.length); return result;
    }
    private OplusRelayControl() {}
}
