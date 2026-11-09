package io.github.yuejie.connect;

import org.json.JSONArray;
import org.json.JSONObject;

/** Discovery is only a routing hint; pairing still verifies identity and bilateral consent. */
final class NfcTapPeer {
    static String select(JSONArray peers, String target) {
        if (peers == null || !NativePairingPolicy.session(target)) return null;
        String selected = null;
        for (int i = 0; i < peers.length(); i++) {
            JSONObject peer = peers.optJSONObject(i);
            JSONObject real = peer == null ? null : peer.optJSONObject("real");
            if (real == null || !"hyper_os4".equals(real.optString("platform"))
                    || !"tablet".equals(real.optString("kind")) || !target.equals(real.optString("nfc_target"))) continue;
            String id = peer.optString("id");
            if (id.isEmpty()) continue;
            if (selected != null && !selected.equals(id)) return null;
            selected = id;
        }
        return selected;
    }
}
