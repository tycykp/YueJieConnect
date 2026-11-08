package io.github.qiangjian.connect;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppRelayDirectoryTest {
    private AppRelayCatalog.Update update() {
        return new AppRelayCatalog.Update(false, List.of(new AppRelayCatalog.Candidate("test.synthetic.app", "Synthetic",
                AppRelayCatalog.Kind.APP, true, true, 1, "test.synthetic.app.Main")));
    }
    @Test public void exactCatalogRoundTripAndStrictSchema() throws Exception {
        JSONObject json = AppRelayCatalogCodec.encode(1, update());
        assertEquals(update(), AppRelayCatalogCodec.decode(json).update());
        AppRelayCodec.validateAction(new JSONObject().put("action", "catalog").put("request_id", "synthetic").put("catalog", json));
        json.put("revision", "1"); assertThrows(IllegalArgumentException.class, () -> AppRelayCatalogCodec.decode(json));
        json.put("revision", 1).getJSONArray("candidates").getJSONObject(0).put("destination", 1.5);
        assertThrows(IllegalArgumentException.class, () -> AppRelayCatalogCodec.decode(json));
    }
    @Test public void onlyRealNativeConsumptionAdvancesRevisionAndReplayDoesNotRepeat() throws Exception {
        int[] delivered = {0}, cleared = {0}; AtomicBoolean fail = new AtomicBoolean(true);
        AppRelayDirectory directory = new AppRelayDirectory(new AppRelayDirectory.Consumer() {
            public void apply(AppRelayCatalog.Update update) { if (fail.get()) throw new IllegalStateException("native_failed"); delivered[0]++; }
            public void clear() { cleared[0]++; }
        }, () -> true);
        assertThrows(IllegalStateException.class, () -> directory.apply(1, update()));
        fail.set(false); assertFalse(directory.apply(1, update())); assertTrue(directory.apply(1, update()));
        assertEquals(1, delivered[0]);
        assertThrows(IllegalArgumentException.class, () -> directory.apply(1, new AppRelayCatalog.Update(true, List.of())));
        directory.close(); assertEquals(1, cleared[0]);
    }
    @Test public void revokedDuringNativeConsumptionWithdrawsAndCannotReportApplied() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true); int[] clear = {0};
        AppRelayDirectory directory = new AppRelayDirectory(new AppRelayDirectory.Consumer() {
            public void apply(AppRelayCatalog.Update update) { current.set(false); }
            public void clear() { clear[0]++; }
        }, current::get);
        assertThrows(IllegalStateException.class, () -> directory.apply(1, update())); assertEquals(1, clear[0]);
        assertThrows(IllegalStateException.class, () -> directory.apply(2, update()));
    }
    @Test public void nativeAndProjectionIdsAreSeparateAndSessionsCannotBeReused() throws Exception {
        AppRelayPeer peer = new AppRelayPeer("native-selected-device", "projection-alias", "a".repeat(64));
        assertTrue(peer.matches(new JSONObject().put("id", "projection-alias").put("session_id", "a".repeat(64))));
        assertFalse(peer.matches(new JSONObject().put("id", "native-selected-device").put("session_id", "a".repeat(64))));
        assertFalse(peer.matches(new JSONObject().put("id", "projection-alias").put("session_id", "b".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> new AppRelayPeer("native", "projection", "old"));
    }
}
