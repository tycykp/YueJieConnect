package io.github.yuejie.connect;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Oplus RelayPayloadCodec seven-byte header + AbilityProfile key/value array. */
final class OplusRelayCodec {
    static AppRelayRequest decode(Context context, ClassLoader loader, byte[] wire, String request) throws Exception {
        OplusRelayPayload payload = OplusRelayPayload.decode(wire);
        String source = payload.sourcePackage();
        if (payload.richText()) return new AppRelayRequest(request, source, AppRelayRequest.Kind.RICH_TEXT,
                List.of(source), "", AppRelayEndpoint.signers(context, source).get(0), "", "", 0, 0, "", payload.json());
        JSONArray values = payload.profiles();
        Class<?> profile = Class.forName("com.oplus.synergy.bean.AbilityProfile", false, loader);
        ArrayList<Object> extension = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject entry = values.getJSONObject(i);
            String key = entry.getString("key"), value = entry.getString("value");
            if (!List.of(OplusRelayPayload.PKG, OplusRelayPayload.TYPE, OplusRelayPayload.ID, "Relay_Base_Key_APP_Name").contains(key)) {
                Object item = profile.getConstructor().newInstance();
                profile.getMethod("setKey", String.class).invoke(item, key);
                profile.getMethod("setValue", String.class).invoke(item, value);
                extension.add(item);
            }
        }
        Class<?> utils = Class.forName("com.oplus.synergy.utils.RelayUtils", false, loader);
        String uri = (String) utils.getMethod("createDeepLink", ArrayList.class, String.class).invoke(null, extension, source);
        boolean browser = payload.type() == 5 && uri != null && (uri.startsWith("https://") || uri.startsWith("http://"));
        return new AppRelayRequest(request, source, browser ? AppRelayRequest.Kind.BROWSER : AppRelayRequest.Kind.APP,
                browser ? List.of() : List.of(source), uri, browser ? "" : AppRelayEndpoint.signers(context, source).get(0), "", "", 0, 0, "");
    }
    private OplusRelayCodec() {}
}
