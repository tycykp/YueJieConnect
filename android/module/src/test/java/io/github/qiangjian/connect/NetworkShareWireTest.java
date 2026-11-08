package io.github.qiangjian.connect;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.qiangjian.connect.NetworkShareSession.*;

public class NetworkShareWireTest {
    private static final String SESSION = "d".repeat(64);
    @Test public void booleansAreNotCoercedAndUnknownKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> NetworkShareWire.decode(SESSION, Map.of("action", "start", "share_id", "r", "upstream", "wifi", "allow_metered", "false")));
        assertThrows(IllegalArgumentException.class, () -> NetworkShareWire.decode(SESSION, Map.of("action", "stop", "share_id", "r", "session_id", SESSION)));
    }
    @Test public void cellularRequiresExplicitMeteredConsent() {
        assertThrows(SecurityException.class, () -> NetworkShareWire.decode(SESSION, Map.of("action", "start", "share_id", "r", "upstream", "cellular", "allow_metered", false)));
    }
    @Test public void identityComesFromAuthenticatedEnvelope() {
        NetworkShareWire.Command c = NetworkShareWire.decode(SESSION, Map.of("action", "join", "share_id", "r", "upstream", "wifi", "allow_metered", false,
                "ssid", "QJ-0000000000000000", "passphrase", "e".repeat(32)));
        assertEquals(SESSION, c.offer().session()); assertEquals("r", c.offer().request());
        assertFalse(c.toString().contains("e".repeat(32)));
    }
    @Test public void credentialsCannotContainQuotesOrBecomeSavedUserNetwork() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkShareEndpoint.Offer(SESSION, "r", "user-network", "e".repeat(32), Upstream.WIFI));
        assertThrows(IllegalArgumentException.class, () -> new NetworkShareEndpoint.Offer(SESSION, "r", "QJ-0000000000000000", "\"".repeat(32), Upstream.WIFI));
    }
    @Test public void receiptRejectsWrongShareAndPreservesRecoveryFailure() {
        Map<String, Object> body = NetworkShareWire.receipt(new View(new Ticket(SESSION, "r", 1), Role.PROVIDER, Upstream.WIFI, Phase.RECOVERY_REQUIRED, 0, "network_restore_unverified"));
        assertEquals(Phase.RECOVERY_REQUIRED, NetworkShareWire.receipt(SESSION, "r", body).phase());
        assertThrows(SecurityException.class, () -> NetworkShareWire.receipt(SESSION, "other", body));
    }
    @Test public void secretOfferRoundTripsWithoutDuplicatingSessionInPayload() {
        NetworkShareEndpoint.Offer original = new NetworkShareEndpoint.Offer(SESSION, "r", "QJ-0000000000000000", "e".repeat(32), Upstream.WIFI);
        Map<String, Object> map = NetworkShareWire.offer(original);
        assertFalse(map.containsKey("session_id")); assertEquals(original, NetworkShareWire.offer(SESSION, "r", map));
        assertThrows(SecurityException.class, () -> NetworkShareWire.offer(SESSION, "other", map));
    }
}
