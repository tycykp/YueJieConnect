package io.github.yuejie.connect;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class TelephonySyncAddressTest {
    @Test public void optionalDisplayAddressRoundTripsButCannotSelectControlTarget() {
        var state = new TelephonySyncController.Snapshot("call", 1, TelephonySyncController.State.RINGING, "+12025550123");
        assertEquals(state, TelephonySyncWire.state(TelephonySyncWire.state(state)));
        assertEquals("", TelephonySyncWire.state(Map.of("call_id", "call", "revision", 1, "state", "ringing")).address());
        assertThrows(TelephonySyncWire.Failure.class, () -> TelephonySyncWire.control(
                new TelephonySyncController.Session("peer", "a".repeat(64)), "request",
                Map.of("call_id", "call", "revision", 1, "action", "end", "address", "+12025550123")));
    }
    @Test public void displayAddressRejectsDialCodesUrisAndOversizeInput() {
        for (String address : new String[]{"*123#", "tel:123", "123;456", "1".repeat(33), "+", "１２３"})
            assertThrows(TelephonySyncWire.Failure.class, () -> TelephonySyncWire.state(
                    Map.of("call_id", "call", "revision", 1, "state", "ringing", "address", address)));
    }
}
