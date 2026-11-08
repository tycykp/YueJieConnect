package io.github.qiangjian.connect;

import org.json.JSONObject;

/** Supplied by the native logical-channel owner from an actual selected phone/pad data-relay peer. */
record AppRelayPeer(String nativeId, String projectionId, String sessionId) {
    interface Resolver { AppRelayPeer selected(String nativePeerId); }
    AppRelayPeer {
        if (nativeId == null || nativeId.isEmpty() || nativeId.length() > 256 || nativeId.codePoints().anyMatch(Character::isISOControl)
                || projectionId == null || projectionId.isEmpty() || projectionId.length() > 256
                || sessionId == null || !sessionId.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid_native_peer_binding");
    }
    boolean matches(JSONObject projection) {
        return projection != null && projectionId.equals(projection.optString("id")) && sessionId.equals(projection.optString("session_id"));
    }
    @Override public String toString() { return "AppRelayPeer[bound]"; }
}
