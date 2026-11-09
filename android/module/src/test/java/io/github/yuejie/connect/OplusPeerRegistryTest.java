package io.github.yuejie.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OplusPeerRegistryTest {
    private record Device(String id,String session,boolean connected){}
    @Test public void liveProjectionReplacesSavedOfflineDeviceWithoutChangingOtherPeers(){
        Device old=new Device("yj:real","old",false),live=new Device("yj:real","new",true),other=new Device("native:other","native",false);
        var input=java.util.List.of(old,other,old);
        var result=OplusPeerRegistry.overlay(input,live,value->((Device)value).id());
        assertEquals(java.util.List.of(live,other),result);
        assertSame(live,result.get(0));assertSame(other,result.get(1));assertSame(old,input.get(0));
    }
    @Test public void forgedPrefixAndForeignIdAreNotOwned() {
        OplusPeerRegistry registry = new OplusPeerRegistry();
        registry.update("yj:real", "session-a");
        assertFalse(registry.owns("yj:forged"));
        assertFalse(registry.owns(null));
        assertTrue(registry.owns("yj:real"));
    }
    @Test public void reconnectWithSameKeyCannotCompleteOldRequest() {
        OplusPeerRegistry registry = new OplusPeerRegistry();
        registry.update("yj:real", "session-a");
        var old = registry.begin("yj:real");
        registry.update("yj:real", "session-b");
        var next = registry.begin("yj:real");
        assertFalse(registry.finish(old));
        assertTrue(registry.finish(next));
        assertFalse(registry.finish(next));
    }
    @Test public void cancellationAndWithdrawalSuppressLateCallbacks() {
        OplusPeerRegistry registry = new OplusPeerRegistry();
        registry.update("yj:real", "session-a");
        var cancelled = registry.begin("yj:real");
        registry.cancel("yj:real");
        assertFalse(registry.finish(cancelled));
        var withdrawn = registry.begin("yj:real");
        registry.update(null, null);
        assertFalse(registry.finish(withdrawn));
        assertThrows(IllegalStateException.class, () -> registry.begin("yj:real"));
    }
    @Test public void metadataRefreshDoesNotInvalidateActiveSession() {
        OplusPeerRegistry registry = new OplusPeerRegistry();
        registry.update("yj:real", "session-a");
        var ticket = registry.begin("yj:real");
        assertFalse(registry.update("yj:real", "session-a"));
        assertTrue(registry.finish(ticket));
    }
    @Test public void boundedPendingStateRecoversAfterCancellation() {
        OplusPeerRegistry registry = new OplusPeerRegistry();
        registry.update("yj:real", "session-a");
        for (int i = 0; i < 32; i++) registry.begin("yj:real");
        assertThrows(IllegalStateException.class, () -> registry.begin("yj:real"));
        registry.cancel("yj:real");
        assertEquals(0, registry.pendingCount());
        assertTrue(registry.finish(registry.begin("yj:real")));
    }
}
