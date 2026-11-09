package io.github.yuejie.connect;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class TelephonySyncSmsStateTest {
    private final TelephonySyncController.Session session = new TelephonySyncController.Session("peer", "a".repeat(64));
    @Test public void unknownReferenceAndCrossSessionCannotAddressProvider() {
        var state = new TelephonySyncSmsState(session);
        String first = state.observed(5, "synthetic_ref");
        assertEquals(first, state.observed(5, "synthetic_ref"));
        assertEquals(1, state.resolve(session, List.of(first, first)).size());
        assertThrows(IllegalStateException.class, () -> state.resolve(session, List.of("synthetic_ref")));
        assertThrows(IllegalStateException.class, () -> state.resolve(new TelephonySyncController.Session("peer", "b".repeat(64)), List.of(first)));
    }
    @Test public void mixedNativeSessionsAndDisconnectedReferencesAreRejected() {
        var state = new TelephonySyncSmsState(session);
        String first = state.observed(5, "ref"), second = state.observed(6, "ref");
        assertNotEquals(first, second);
        assertThrows(IllegalStateException.class, () -> state.resolve(session, List.of(first, second)));
        state.nativeDisconnected(5);
        assertThrows(IllegalStateException.class, () -> state.resolve(session, List.of(first)));
        assertEquals(6, state.resolve(session, List.of(second)).get(0).session());
    }
    @Test public void shutdownRejectsExistingReferencesAndNewBindings() {
        var state = new TelephonySyncSmsState(session); String first = state.observed(5, "ref"); state.close();
        assertThrows(IllegalStateException.class, () -> state.resolve(session, List.of(first)));
        assertThrows(IllegalStateException.class, () -> state.observed(5, "ref"));
    }
}
