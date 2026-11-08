package io.github.qiangjian.connect;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenCastNativeSessionTest {
    private final ScreenCastSession.Scope scope = new ScreenCastSession.Scope("c".repeat(64), 9);
    private final ScreenCastFormat format = new ScreenCastFormat(640, 480, 30, 1_000_000);
    private final Object owner = new Object();
    private final AtomicBoolean granted = new AtomicBoolean(true);
    private final AtomicLong clock = new AtomicLong(100);
    private final class Fixture implements ScreenCastNativeSession.Control, ScreenCastNativeSession.Endpoint,
            ScreenCastNativeSession.Listener {
        final List<String> events = new ArrayList<>();
        final List<ScreenCastSession.Receipt> receipts = new ArrayList<>(), streaming = new ArrayList<>();
        boolean failPrepare, failOffer, failReceive, failCleanup;
        ScreenCastNativeSession session;
        public void prepared(ScreenCastSession.Scope s, ScreenCastFormat f) {
            assertEquals(scope, s); assertEquals(format, f); events.add("prepared");
            if (failPrepare) throw new IllegalStateException("synthetic");
        }
        public void rendered(ScreenCastSession.Receipt r) { receipts.add(r); }
        public void requestKeyFrame(ScreenCastSession.Scope s) { events.add("control-keyframe"); }
        public void stopped(ScreenCastSession.Scope s, String code) { events.add("stop:" + code); }
        public boolean offer(ScreenCastPacket packet) {
            if (failOffer) throw new IllegalStateException("synthetic"); events.add("offer"); return true;
        }
        public ScreenCastFrameQueue.Offer receive(ScreenCastPacket packet) {
            if (failReceive) throw new IllegalStateException("synthetic"); events.add("decode"); return ScreenCastFrameQueue.Offer.ACCEPTED;
        }
        public void requestKeyFrame() { events.add("encoder-keyframe"); }
        public void revokeInput() {
            assertFalse(session.streaming(owner, scope)); events.add("input-revoked");
            if (failCleanup) throw new IllegalStateException("synthetic");
        }
        public void stopNative() { events.add("native-stopped"); session.close(); }
        public void closeMedia() { events.add("media-closed"); }
        public void onStreaming(ScreenCastSession.Receipt r) { streaming.add(r); }
        public void onClosed(String code) { events.add("closed:" + code); }
        Fixture(ScreenCastSession.Role role) {
            session = new ScreenCastNativeSession(owner, scope, format, role, granted::get, clock::get, this, this, this);
        }
    }
    private ScreenCastPacket frame() { return new ScreenCastPacket(9, 1, 100, ScreenCastPacket.KEY, new byte[]{1,2,3}); }
    @Test public void sourceActualDisplayAndMatchedRenderGateInput() {
        Fixture f = new Fixture(ScreenCastSession.Role.SOURCE);
        var source = f.session.sourceListener(owner);
        assertTrue(f.session.remotePrepared(scope, format));
        assertFalse(source.offer(frame())); assertFalse(f.session.streaming(owner, scope));
        source.onPrepared(42); source.onPrepared(42); assertEquals(List.of("prepared"), f.events);
        assertTrue(source.offer(frame())); assertTrue(f.streaming.isEmpty());
        assertFalse(f.session.remoteRendered(new ScreenCastSession.Receipt(scope, 1, 101)));
        assertTrue(f.session.remoteRendered(new ScreenCastSession.Receipt(scope, 1, 100)));
        assertTrue(f.session.streaming(owner, scope)); assertEquals(1, f.streaming.size());
        assertFalse(f.session.streaming(new Object(), scope));
        assertFalse(f.session.streaming(owner, new ScreenCastSession.Scope(scope.sessionId(), 10)));
        granted.set(false); assertFalse(f.session.streaming(owner, scope));
        assertEquals(List.of("prepared", "offer", "input-revoked", "native-stopped", "media-closed",
                "stop:screen_permission_revoked", "closed:screen_permission_revoked"), f.events);
    }
    @Test public void sinkReceiptOnlyAfterAcceptedFrameAndRealCallback() {
        Fixture f = new Fixture(ScreenCastSession.Role.SINK); var sink = f.session.sinkListener(owner);
        sink.onPrepared(); sink.onFrameRendered(100, 1); assertTrue(f.receipts.isEmpty());
        f.session.remotePrepared(scope, format);
        assertEquals(ScreenCastFrameQueue.Offer.ACCEPTED, f.session.receive(scope, frame()));
        assertTrue(f.receipts.isEmpty()); sink.onFrameRendered(999, 2); assertTrue(f.receipts.isEmpty());
        sink.onFrameRendered(100, 3); assertEquals(List.of(new ScreenCastSession.Receipt(scope,1,100)), f.receipts);
        assertTrue(f.session.streaming(owner, scope));
        sink.onStopped(); sink.onPrepared(); sink.onFrameRendered(100,4);
        assertEquals(1, f.receipts.size()); assertFalse(f.session.streaming(owner, scope));
        assertEquals(1, f.events.stream().filter("native-stopped"::equals).count());
    }
    @Test public void wrongOwnerAndRoleNeverAcquireListeners() {
        Fixture f = new Fixture(ScreenCastSession.Role.SOURCE);
        assertThrows(IllegalArgumentException.class, () -> f.session.sourceListener(new Object()));
        assertThrows(IllegalArgumentException.class, () -> f.session.sinkListener(owner));
        f.session.close(); assertThrows(IllegalArgumentException.class, () -> f.session.sourceListener(owner));
    }
    @Test public void nativeDisplayChangeRequiresFreshStream() {
        Fixture f = new Fixture(ScreenCastSession.Role.SOURCE); var source = f.session.sourceListener(owner);
        source.onPrepared(42); source.onPrepared(43);
        assertEquals(ScreenCastSession.State.CLOSED, f.session.state());
        assertTrue(f.events.contains("closed:screen_native_display_changed")); assertFalse(source.offer(frame()));
    }
    @Test public void remoteStopIsScopedAndHasNoEchoEvenWithNativeReentry() {
        Fixture f = new Fixture(ScreenCastSession.Role.SINK);
        assertFalse(f.session.remoteStopped(new ScreenCastSession.Scope(scope.sessionId(),10)));
        assertTrue(f.events.isEmpty()); assertTrue(f.session.remoteStopped(scope));
        f.session.close(); assertFalse(f.session.remoteStopped(scope));
        assertEquals(List.of("input-revoked","native-stopped","media-closed","closed:screen_remote_stopped"), f.events);
    }
    @Test public void preparationFailureAndCleanupExceptionStillRevokeAllResources() {
        Fixture f = new Fixture(ScreenCastSession.Role.SINK); f.failPrepare = f.failCleanup = true;
        f.session.sinkListener(owner).onPrepared();
        assertEquals(List.of("prepared","input-revoked","native-stopped","media-closed",
                "stop:screen_control_failed","closed:screen_control_failed"), f.events);
    }
    @Test public void mediaExceptionsCloseWithoutClaimingStreamOrReceipt() {
        Fixture source = new Fixture(ScreenCastSession.Role.SOURCE); var encoder = source.session.sourceListener(owner);
        encoder.onPrepared(42); source.session.remotePrepared(scope,format); source.failOffer = true;
        assertFalse(encoder.offer(frame())); assertTrue(source.events.contains("closed:screen_media_failed"));
        Fixture sink = new Fixture(ScreenCastSession.Role.SINK); sink.session.sinkListener(owner).onPrepared();
        sink.session.remotePrepared(scope,format); sink.failReceive = true;
        assertEquals(ScreenCastFrameQueue.Offer.CLOSED,sink.session.receive(scope,frame()));
        assertTrue(sink.receipts.isEmpty()); assertTrue(sink.streaming.isEmpty());
    }
    @Test public void silentPeerTimeoutClosesOnceAndFencesLateNativeCallbacks() {
        Fixture f = new Fixture(ScreenCastSession.Role.SOURCE); var source = f.session.sourceListener(owner);
        source.onPrepared(42); f.session.remotePrepared(scope,format); clock.addAndGet(5_000); f.session.tick();
        source.onPrepared(42); source.onStopped(); source.onFailure("private synthetic text");
        assertEquals(List.of("prepared","input-revoked","native-stopped","media-closed",
                "stop:first_frame_timeout","closed:first_frame_timeout"), f.events);
    }
    @Test public void replacingNativeSurfaceRevokesOldRenderAndInputLease() {
        Fixture f = new Fixture(ScreenCastSession.Role.SINK); var sink = f.session.sinkListener(owner);
        sink.onPrepared(); f.session.remotePrepared(scope,format); f.session.receive(scope,frame());
        sink.onFrameRendered(100,1); assertTrue(f.session.streaming(owner,scope));
        sink.onPrepared(); assertFalse(f.session.streaming(owner,scope));
        sink.onFrameRendered(100,2); assertEquals(1,f.receipts.size());
        assertTrue(f.events.contains("closed:screen_native_surface_changed"));
        assertEquals(1,f.events.stream().filter("input-revoked"::equals).count());
    }
}
