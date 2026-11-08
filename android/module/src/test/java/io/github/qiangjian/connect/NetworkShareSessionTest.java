package io.github.qiangjian.connect;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.qiangjian.connect.NetworkShareSession.*;

public class NetworkShareSessionTest {
    private static final String SESSION = "a".repeat(64);
    private NetworkShareSession fresh() { NetworkShareSession s = new NetworkShareSession(); s.bind(SESSION); return s; }
    private Ticket start(NetworkShareSession s) {
        Ticket t = s.request(SESSION, "test:1", Role.PROVIDER, Upstream.WIFI, 0);
        assertTrue(s.confirm(t, 1)); return t;
    }
    @Test public void hotspotIsNotShareUntilConsumerProvesJoin() {
        NetworkShareSession s = fresh(); Ticket t = start(s);
        assertTrue(s.ready(t, Upstream.WIFI, true, 2)); assertEquals(Phase.READY, s.view().phase());
        assertThrows(IllegalArgumentException.class, () -> s.attached(t, false, 3));
        assertTrue(s.attached(t, true, 4)); assertEquals(Phase.ACTIVE, s.view().phase());
    }
    @Test public void wrongUpstreamRequiresRollback() {
        NetworkShareSession s = fresh(); Ticket t = start(s);
        assertFalse(s.ready(t, Upstream.CELLULAR, true, 2)); assertEquals(Phase.STOPPING, s.view().phase());
        s.restored(t, true); assertEquals(Phase.FAILED, s.view().phase());
    }
    @Test public void confirmationTimeoutNeverStartsNativeChange() {
        NetworkShareSession s = fresh(); Ticket t = s.request(SESSION, "r", Role.PROVIDER, Upstream.WIFI, 0);
        assertFalse(s.confirm(t, 30_000)); assertEquals(Phase.STOPPING, s.view().phase());
    }
    @Test public void lostControlHasBoundedLease() {
        NetworkShareSession s = fresh(); Ticket t = start(s);
        s.ready(t, Upstream.WIFI, true, 2); s.attached(t, true, 3);
        assertNull(s.expire(120_002)); assertEquals(t, s.expire(120_003));
        assertThrows(IllegalStateException.class, () -> s.renew(t, 120_004));
    }
    @Test public void cancelledStartCannotResurrectFromLateCallback() {
        NetworkShareSession s = fresh(); Ticket t = start(s); s.stop(t, "cancelled");
        assertFalse(s.ready(t, Upstream.WIFI, true, 2)); s.restored(t, true);
        assertFalse(s.ready(t, Upstream.WIFI, true, 3));
    }
    @Test public void failedRestoreRetainsOwnershipAndCanRetry() {
        NetworkShareSession s = fresh(); Ticket t = start(s); s.stop(t, "cancelled"); s.restored(t, false);
        assertThrows(IllegalStateException.class, () -> s.bind("b".repeat(64)));
        assertThrows(IllegalStateException.class, () -> s.request(SESSION, "new", Role.PROVIDER, Upstream.WIFI, 4));
        assertTrue(s.stop(t, "cancelled")); s.restored(t, true); s.bind("b".repeat(64));
    }
    @Test public void replayCannotCreateAnotherHotspot() {
        NetworkShareSession s = fresh(); Ticket t = start(s); s.stop(t, null); s.restored(t, true);
        assertEquals(t, s.request(SESSION, "test:1", Role.PROVIDER, Upstream.WIFI, 4));
        assertFalse(s.confirm(t, 5));
        assertThrows(IllegalArgumentException.class, () -> s.request(SESSION, "test:1", Role.CONSUMER, Upstream.WIFI, 6));
    }
    @Test public void oldSessionAndOldTicketCannotControlNewResources() {
        NetworkShareSession s = fresh(); Ticket old = start(s); s.stop(old, null); s.restored(old, true);
        s.bind("b".repeat(64));
        assertThrows(IllegalStateException.class, () -> s.request(SESSION, "r", Role.PROVIDER, Upstream.WIFI, 4));
        Ticket next = s.request("b".repeat(64), "test:1", Role.PROVIDER, Upstream.WIFI, 5);
        assertNotEquals(old, next); assertThrows(IllegalStateException.class, () -> s.stop(old, null));
    }
    @Test public void processRestartOnlyRecoversOwnershipAndNeverResumesAuthorization() {
        NetworkShareSession recovered = new NetworkShareSession();
        Ticket ticket = new Ticket(SESSION, "synthetic:crash", 9);
        recovered.recover(ticket, Role.CONSUMER, Upstream.WIFI);
        assertEquals(Phase.RECOVERY_REQUIRED, recovered.view().phase());
        assertThrows(IllegalStateException.class, () -> recovered.renew(ticket, 0));
        assertThrows(IllegalStateException.class, () -> recovered.bind("b".repeat(64)));
        assertThrows(IllegalStateException.class, () -> recovered.request(SESSION, "new", Role.CONSUMER, Upstream.WIFI, 0));
        recovered.stop(ticket, "owner_process_restarted"); recovered.restored(ticket, true);
        Ticket next = recovered.request(SESSION, "new", Role.CONSUMER, Upstream.WIFI, 1);
        assertTrue(next.generation() > ticket.generation());
    }
    @Test public void historicalShareReplayCannotReplaceCurrentNativeOwnership() {
        NetworkShareSession s = fresh(); Ticket old = start(s);
        s.stop(old, null); s.restored(old, true);
        Ticket current = s.request(SESSION, "test:2", Role.CONSUMER, Upstream.WIFI, 4);
        assertTrue(s.confirm(current, 5));
        View before = s.view();
        assertThrows(IllegalStateException.class, () -> s.request(SESSION, "test:1", Role.PROVIDER, Upstream.WIFI, 6));
        assertEquals(before, s.view()); assertTrue(s.ownsResource());
        s.stop(current, null); s.restored(current, true);
        assertThrows(IllegalStateException.class, () -> s.request(SESSION, "test:1", Role.PROVIDER, Upstream.WIFI, 7));
        assertEquals(current, s.view().ticket());
    }
}
