package io.github.yuejie.connect;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NfcTapPeerTest {
    private static final String TARGET = "ab".repeat(32);
    private JSONObject peer(String id, String target, String platform, String kind) throws Exception {
        return new JSONObject().put("id", id).put("real", new JSONObject().put("nfc_target", target)
                .put("platform", platform).put("kind", kind));
    }

    @Test public void selectsTouchedTabletInsteadOfFirstDiscoveredPeer() throws Exception {
        JSONArray peers = new JSONArray().put(peer("other", "cd".repeat(32), "hyper_os4", "tablet"))
                .put(peer("touched", TARGET, "hyper_os4", "tablet"));
        assertEquals("touched", NfcTapPeer.select(peers, TARGET));
    }

    @Test public void rejectsMissingInvalidAndAmbiguousTargets() throws Exception {
        assertNull(NfcTapPeer.select(null, TARGET));
        assertNull(NfcTapPeer.select(new JSONArray().put(new JSONObject()), TARGET));
        assertNull(NfcTapPeer.select(new JSONArray(), ""));
        assertNull(NfcTapPeer.select(new JSONArray().put(peer("phone", TARGET, "hyper_os4", "phone")), TARGET));
        assertNull(NfcTapPeer.select(new JSONArray().put(peer("wrong", TARGET, "color_os17", "tablet")), TARGET));
        assertNull(NfcTapPeer.select(new JSONArray().put(peer("one", TARGET, "hyper_os4", "tablet"))
                .put(peer("two", TARGET, "hyper_os4", "tablet")), TARGET));
    }

    @Test public void duplicateAdvertisementOfSameIdentityIsAllowed() throws Exception {
        JSONObject peer = peer("same", TARGET, "hyper_os4", "tablet");
        assertEquals("same", NfcTapPeer.select(new JSONArray().put(peer).put(peer), TARGET));
    }
}
