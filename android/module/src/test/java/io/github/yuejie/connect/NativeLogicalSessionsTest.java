package io.github.yuejie.connect;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class NativeLogicalSessionsTest {
    private static final String SESSION="a".repeat(64), CHANNEL="b".repeat(32);
    private static final Set<String> CLIPBOARD=Set.of("clipboard_write");
    @Test public void nonClipboardBusinessCanOpenAndRevocationCannotResurrectIt(){
        NativeLogicalSessions s=new NativeLogicalSessions();s.session(SESSION);
        Set<String> operations=Set.of("app_relay","media_control");
        assertTrue(s.open(SESSION,CHANNEL,operations,operations).open());
        assertThrows(IllegalStateException.class,()->s.open(SESSION,CHANNEL,Set.of("app_relay"),operations));
        assertFalse(s.state(SESSION,CHANNEL,Set.of("app_relay")).open());
        assertThrows(IllegalStateException.class,()->s.open(SESSION,CHANNEL,operations,operations));
    }
    @Test public void controlAndUnimplementedOperationsCannotBackAChannel(){
        NativeLogicalSessions s=new NativeLogicalSessions();s.session(SESSION);
        for(String operation:Set.of("logical_open","logical_state","device_profile","screen_video","network_share"))
            assertThrows(IllegalArgumentException.class,()->s.open(SESSION,CHANNEL,Set.of(operation),Set.of(operation)));
    }
    @Test public void openRequiresAnActualRegisteredBusinessAndBindsOperationSet(){
        NativeLogicalSessions s=new NativeLogicalSessions();s.session(SESSION);
        assertThrows(IllegalStateException.class,()->s.open(SESSION,CHANNEL,CLIPBOARD,Set.of()));
        assertTrue(s.open(SESSION,CHANNEL,CLIPBOARD,CLIPBOARD).open());
        assertEquals(CLIPBOARD,s.open(SESSION,CHANNEL,CLIPBOARD,CLIPBOARD).operations());
        assertThrows(IllegalArgumentException.class,()->s.open(SESSION,CHANNEL,Set.of("unknown"),Set.of("unknown")));
    }
    @Test public void closeWinsBeforeOpenAndCannotResurrect(){
        NativeLogicalSessions s=new NativeLogicalSessions();s.session(SESSION);
        assertFalse(s.close(SESSION,CHANNEL).open());
        assertThrows(IllegalStateException.class,()->s.open(SESSION,CHANNEL,CLIPBOARD,CLIPBOARD));
        assertFalse(s.close(SESSION,CHANNEL).open());
    }
    @Test public void temporaryProjectionLossDoesNotEraseReplayTombstones(){
        NativeLogicalSessions s=new NativeLogicalSessions();s.session(SESSION);
        s.open(SESSION,CHANNEL,CLIPBOARD,CLIPBOARD);s.session(null);
        assertThrows(IllegalStateException.class,()->s.state(SESSION,CHANNEL,CLIPBOARD));
        s.session(SESSION);
        assertFalse(s.state(SESSION,CHANNEL,CLIPBOARD).open());
        assertThrows(IllegalStateException.class,()->s.open(SESSION,CHANNEL,CLIPBOARD,CLIPBOARD));
        s.session("c".repeat(64));assertFalse(s.hasRecords());
    }
    @Test public void lostExecutorClosesLeaseAndSessionChangeRejectsLateRequests(){
        NativeLogicalSessions s=new NativeLogicalSessions();s.session(SESSION);s.open(SESSION,CHANNEL,CLIPBOARD,CLIPBOARD);
        assertFalse(s.state(SESSION,CHANNEL,Set.of()).open());
        assertThrows(IllegalStateException.class,()->s.open(SESSION,CHANNEL,CLIPBOARD,CLIPBOARD));
        s.session("c".repeat(64));assertFalse(s.hasRecords());
        assertThrows(IllegalStateException.class,()->s.close(SESSION,CHANNEL));
    }
    @Test public void capacityAlwaysAllowsClosingAnOwnedChannel(){
        NativeLogicalSessions s=new NativeLogicalSessions();s.session(SESSION);
        for(int i=0;i<16;i++)s.open(SESSION,String.format("%032x",i),CLIPBOARD,CLIPBOARD);
        assertThrows(IllegalStateException.class,()->s.open(SESSION,CHANNEL,CLIPBOARD,CLIPBOARD));
        s.close(SESSION,String.format("%032x",0));assertTrue(s.open(SESSION,CHANNEL,CLIPBOARD,CLIPBOARD).open());
    }
    @Test public void lostSourceExpiresLeaseWithoutAllowingLateOpenToResurrect(){
        java.util.concurrent.atomic.AtomicLong now=new java.util.concurrent.atomic.AtomicLong();
        NativeLogicalSessions s=new NativeLogicalSessions(now::get);s.session(SESSION);
        s.open(SESSION,CHANNEL,CLIPBOARD,CLIPBOARD);
        now.set(7_999);assertTrue(s.state(SESSION,CHANNEL,CLIPBOARD).open());
        now.set(15_998);assertTrue(s.state(SESSION,CHANNEL,CLIPBOARD).open());
        now.set(23_998);assertFalse(s.state(SESSION,CHANNEL,CLIPBOARD).open());
        assertThrows(IllegalStateException.class,()->s.open(SESSION,CHANNEL,CLIPBOARD,CLIPBOARD));
        for(int i=0;i<16;i++)s.open(SESSION,String.format("%032x",i),CLIPBOARD,CLIPBOARD);
        now.addAndGet(8_000);
        assertTrue(s.open(SESSION,"c".repeat(32),CLIPBOARD,CLIPBOARD).open());
    }
}
