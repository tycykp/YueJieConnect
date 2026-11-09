package io.github.yuejie.connect;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import static org.junit.Assert.*;

public class OplusScreenSessionLeaseTest {
    private final ScreenCastSession.Scope scope = new ScreenCastSession.Scope("c".repeat(64),7);
    private final ScreenCastFormat format = new ScreenCastFormat(640,480,30,1_000_000);
    private final Object device = new Object();
    private final AtomicBoolean current = new AtomicBoolean(true);
    private final AtomicLong clock = new AtomicLong(100);
    private final class Factory implements OplusScreenSessionLease.Factory, ScreenCastNativeSession.Control,
            ScreenCastNativeSession.Endpoint, ScreenCastNativeSession.Listener {
        final CompletableFuture<ScreenCastNativeSession> opening = new CompletableFuture<>();
        final List<String> events = new ArrayList<>();
        BooleanSupplier permit; int opens, cancellations, streaming;
        Object nativeManager; String nativeBus;
        public CompletionStage<ScreenCastNativeSession> openAfterNativeConsent(Object actual,BooleanSupplier permit) {
            assertSame(device,actual); opens++; this.permit=permit; return opening;
        }
        public void cancel() { cancellations++; }
        public void nativeSource(Object actual,Object manager,String busId) { assertSame(device,actual);nativeManager=manager;nativeBus=busId; }
        public void failed(String code) { events.add(code); }
        public boolean offer(ScreenCastPacket packet) { events.add("offered"); return true; }
        public ScreenCastFrameQueue.Offer receive(ScreenCastPacket packet) { return ScreenCastFrameQueue.Offer.ACCEPTED; }
        public void requestKeyFrame() { }
        public void revokeInput() { events.add("input-revoked"); }
        public void stopNative() { events.add("native-stopped"); }
        public void closeMedia() { events.add("media-closed"); }
        public void prepared(ScreenCastSession.Scope scope,ScreenCastFormat format) { events.add("prepared"); }
        public void rendered(ScreenCastSession.Receipt receipt) { }
        public void requestKeyFrame(ScreenCastSession.Scope scope) { }
        public void stopped(ScreenCastSession.Scope scope,String code) { }
        public void onStreaming(ScreenCastSession.Receipt receipt) { streaming++; }
        public void onClosed(String code) { events.add(code); }
        ScreenCastNativeSession session(Object owner,ScreenCastSession.Scope actualScope) {
            return new ScreenCastNativeSession(owner,actualScope,format,ScreenCastSession.Role.SOURCE,
                    () -> permit != null && permit.getAsBoolean(),clock::get,this,this,this);
        }
    }
    private OplusScreenSessionLease lease(Factory f) {
        return new OplusScreenSessionLease(device,scope,120_100,current::get,clock::get,f);
    }
    private ScreenCastPacket frame() { return new ScreenCastPacket(7,1,100,ScreenCastPacket.KEY,new byte[]{1,2,3}); }
    @Test public void realAllowStartsAsyncFactoryAndBuffersOnlyActualDisplayIdentity() {
        Factory f = new Factory(); var lease = lease(f);
        assertTrue(lease.current()); assertFalse(lease.offer(frame())); assertEquals(0,f.opens);
        lease.onNativePrompted(device); assertEquals(0,f.opens);
        lease.onNativeAllowed(device); lease.onNativeAllowed(device); assertEquals(1,f.opens);
        assertTrue(f.permit.getAsBoolean()); lease.onPrepared(42); assertFalse(lease.offer(frame())); assertFalse(f.events.contains("prepared"));
        var session = f.session(device,scope); assertTrue(session.remotePrepared(scope,format)); f.opening.complete(session);
        assertEquals(OplusScreenSessionLease.State.ACTIVE,lease.state()); assertTrue(f.events.contains("prepared"));
        assertTrue(lease.offer(frame())); assertEquals(0,f.streaming);
        assertTrue(session.remoteRendered(new ScreenCastSession.Receipt(scope,1,100))); assertEquals(1,f.streaming);
        lease.onStopped(); assertEquals(1,f.cancellations); assertFalse(lease.current()); assertFalse(f.permit.getAsBoolean());
    }
    @Test public void wrongOwnerOrPreparationWithoutNativeAllowCannotOpenMedia() {
        Factory f = new Factory(); var lease = lease(f); lease.onNativeAllowed(new Object());
        assertEquals(0,f.opens); assertEquals(List.of("screen_native_owner_mismatch"),f.events);
        Factory g = new Factory(); var early = lease(g); early.onPrepared(42);
        assertEquals(0,g.opens); assertEquals(List.of("screen_native_preparation_mismatch"),g.events);
    }
    @Test public void cancelledOpeningClosesLateSessionWithoutReturningToActive() {
        Factory f = new Factory(); var lease = lease(f); lease.onNativeAllowed(device); lease.close();
        var late = f.session(device,scope); f.opening.complete(late);
        assertEquals(ScreenCastSession.State.CLOSED,late.state()); assertEquals(OplusScreenSessionLease.State.CLOSED,lease.state());
        assertEquals(1,f.cancellations); assertFalse(f.events.contains("prepared"));
    }
    @Test public void silentConsentAndSilentMediaOpenHaveSeparateBoundedDeadlines() {
        Factory f = new Factory(); var lease = lease(f); clock.set(120_100); lease.tick();
        lease.onNativeAllowed(device); assertEquals(0,f.opens); assertEquals(List.of("screen_native_consent_timeout"),f.events);
        clock.set(100); Factory g = new Factory(); var opening = lease(g); opening.onNativeAllowed(device);
        clock.set(10_100); opening.tick(); assertEquals(List.of("screen_media_open_timeout"),g.events);
        assertFalse(g.permit.getAsBoolean());
    }
    @Test public void revokedRequestClosesCurrentStreamAndNeverRestoresPermission() {
        Factory f = new Factory(); var lease = lease(f); lease.onNativeAllowed(device); var session=f.session(device,scope);
        f.opening.complete(session); current.set(false); lease.tick();
        assertEquals(ScreenCastSession.State.CLOSED,session.state()); assertEquals(1,f.cancellations);
        current.set(true); lease.onNativeAllowed(device); assertFalse(lease.current()); assertEquals(1,f.opens);
        assertEquals(1,f.events.stream().filter("native-stopped"::equals).count());
    }
    @Test public void factoryMustReturnSameNativeOwnerAndAuthenticatedStream() {
        Factory f = new Factory(); var lease=lease(f); lease.onNativeAllowed(device); var wrongOwner=f.session(new Object(),scope);
        f.opening.complete(wrongOwner); assertEquals(ScreenCastSession.State.CLOSED,wrongOwner.state());
        assertTrue(f.events.contains("screen_native_owner_mismatch"));
        Factory g=new Factory(); var other=lease(g); other.onNativeAllowed(device);
        var wrongStream=g.session(device,new ScreenCastSession.Scope(scope.sessionId(),8)); g.opening.complete(wrongStream);
        assertEquals(ScreenCastSession.State.CLOSED,wrongStream.state()); assertTrue(g.events.contains("screen_native_stream_mismatch"));
    }
    @Test public void factoryFailureNeverCopiesUntrustedExceptionText() {
        Factory f=new Factory(); var lease=lease(f); lease.onNativeAllowed(device);
        f.opening.completeExceptionally(new IllegalStateException("synthetic private message"));
        assertEquals(List.of("screen_media_open_failed"),f.events); assertEquals(1,f.cancellations);
    }
    @Test public void changedNativeDisplayBeforeMediaReadyCancelsOpening() {
        Factory f=new Factory(); var lease=lease(f); lease.onNativeAllowed(device); lease.onPrepared(42); lease.onPrepared(43);
        assertEquals(OplusScreenSessionLease.State.CLOSED,lease.state()); assertEquals(1,f.cancellations);
        var late=f.session(device,scope); f.opening.complete(late); assertEquals(ScreenCastSession.State.CLOSED,late.state());
    }
    @Test public void nativeManagerMappingRequiresAllowedExactDeviceAndCannotSwitchOwners() {
        Factory f=new Factory();var lease=lease(f);Object manager=new Object();
        lease.onNativeAllowed(device);lease.onNativeSource(device,manager,"native-bus");assertSame(manager,f.nativeManager);
        assertEquals("native-bus",f.nativeBus);lease.onNativeSource(device,new Object(),"native-bus");
        assertEquals(OplusScreenSessionLease.State.CLOSED,lease.state());assertSame(manager,f.nativeManager);
        assertEquals(1,f.cancellations);
        Factory g=new Factory();var early=lease(g);early.onNativeSource(device,new Object(),"native-bus");
        assertNull(g.nativeManager);assertEquals(0,g.opens);
    }
}
