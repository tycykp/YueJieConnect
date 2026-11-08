package io.github.qiangjian.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OplusPeerRegistryTest {
    @Test public void forgedPrefixAndForeignIdAreNotOwned() {
        OplusPeerRegistry registry = new OplusPeerRegistry();
        registry.update("qj:real", "session-a");
        assertFalse(registry.owns("qj:forged"));
        assertFalse(registry.owns(null));
        assertTrue(registry.owns("qj:real"));
    }
    @Test public void reconnectWithSameKeyCannotCompleteOldRequest() {
        OplusPeerRegistry registry = new OplusPeerRegistry();
        registry.update("qj:real", "session-a");
        var old = registry.begin("qj:real");
        registry.update("qj:real", "session-b");
        var next = registry.begin("qj:real");
        assertFalse(registry.finish(old));
        assertTrue(registry.finish(next));
        assertFalse(registry.finish(next));
    }
    @Test public void cancellationAndWithdrawalSuppressLateCallbacks() {
        OplusPeerRegistry registry = new OplusPeerRegistry();
        registry.update("qj:real", "session-a");
        var cancelled = registry.begin("qj:real");
        registry.cancel("qj:real");
        assertFalse(registry.finish(cancelled));
        var withdrawn = registry.begin("qj:real");
        registry.update(null, null);
        assertFalse(registry.finish(withdrawn));
        assertThrows(IllegalStateException.class, () -> registry.begin("qj:real"));
    }
    @Test public void metadataRefreshDoesNotInvalidateActiveSession() {
        OplusPeerRegistry registry = new OplusPeerRegistry();
        registry.update("qj:real", "session-a");
        var ticket = registry.begin("qj:real");
        assertFalse(registry.update("qj:real", "session-a"));
        assertTrue(registry.finish(ticket));
    }
    @Test public void boundedPendingStateRecoversAfterCancellation() {
        OplusPeerRegistry registry = new OplusPeerRegistry();
        registry.update("qj:real", "session-a");
        for (int i = 0; i < 32; i++) registry.begin("qj:real");
        assertThrows(IllegalStateException.class, () -> registry.begin("qj:real"));
        registry.cancel("qj:real");
        assertEquals(0, registry.pendingCount());
        assertTrue(registry.finish(registry.begin("qj:real")));
    }
}
