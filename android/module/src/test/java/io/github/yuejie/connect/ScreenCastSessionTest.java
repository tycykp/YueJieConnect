package io.github.yuejie.connect;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.yuejie.connect.ScreenCastSession.*;

public class ScreenCastSessionTest {
    private final Scope scope = new Scope("c".repeat(64), 9);
    private final ScreenCastFormat format = new ScreenCastFormat(640, 480, 30, 1_000_000);
    private final AtomicLong clock = new AtomicLong(100);
    private final AtomicBoolean allowed = new AtomicBoolean(true);
    private final class Endpoint implements Transport, Listener {
        boolean capacity = true;
        int sent, keyRequests;
        final List<Receipt> receipts = new ArrayList<>(), streaming = new ArrayList<>();
        final List<String> closed = new ArrayList<>();
        public boolean offer(Scope scope, ScreenCastPacket packet) { if (capacity) sent++; return capacity; }
        public void rendered(Receipt receipt) { receipts.add(receipt); }
        public void requestKeyFrame(Scope scope) { keyRequests++; }
        public void onStreaming(Receipt receipt) { streaming.add(receipt); }
        public void onClosed(String code) { closed.add(code); }
    }
    private ScreenCastSession session(Role role, Endpoint endpoint) {
        return new ScreenCastSession(scope, format, role, allowed::get, clock::get, endpoint, endpoint);
    }
    private ScreenCastPacket frame(long sequence, long pts) { return new ScreenCastPacket(9, sequence, pts, ScreenCastPacket.KEY, new byte[]{1,2,3}); }
    private void ready(ScreenCastSession session) { assertTrue(session.localPrepared()); assertTrue(session.remotePrepared(scope, format)); }

    @Test public void sourceNeedsBothPreparationAndMatchedActualReceipt() {
        Endpoint e = new Endpoint(); ScreenCastSession s = session(Role.SOURCE, e);
        assertFalse(s.offer(frame(1, 100))); assertEquals(0, e.sent);
        assertTrue(s.localPrepared()); assertFalse(s.offer(frame(1, 100)));
        assertTrue(s.remotePrepared(scope, format)); assertTrue(s.offer(frame(1, 100)));
        assertEquals(State.WAITING_FIRST_FRAME, s.state());
        assertFalse(s.renderedReceipt(new Receipt(new Scope("d".repeat(64), 9), 1, 100)));
        assertFalse(s.renderedReceipt(new Receipt(scope, 1, 101)));
        assertFalse(s.renderedReceipt(new Receipt(scope, 2, 100)));
        assertTrue(s.renderedReceipt(new Receipt(scope, 1, 100))); assertEquals(State.STREAMING, s.state());
        assertFalse(s.renderedReceipt(new Receipt(scope, 1, 100))); assertEquals(1, e.streaming.size());
    }
    @Test public void backpressureAndDuplicateFramesCannotProduceReceipt() {
        Endpoint e = new Endpoint(); ScreenCastSession s = session(Role.SOURCE, e); ready(s);
        e.capacity = false; assertFalse(s.offer(frame(1, 100)));
        assertFalse(s.renderedReceipt(new Receipt(scope, 1, 100)));
        e.capacity = true; assertFalse(s.offer(frame(1, 100))); assertTrue(s.offer(frame(2, 200)));
        assertTrue(s.renderedReceipt(new Receipt(scope, 2, 200))); assertEquals(1, e.sent);
    }
    @Test public void sinkRequiresAcceptedDecoderFrameAndActualRenderedCallback() {
        Endpoint e = new Endpoint(); ScreenCastSession s = session(Role.SINK, e); ready(s);
        Receiver accepted = packet -> ScreenCastFrameQueue.Offer.ACCEPTED;
        Receiver dropped = packet -> ScreenCastFrameQueue.Offer.NEED_KEY_FRAME;
        assertEquals(ScreenCastFrameQueue.Offer.NEED_KEY_FRAME, s.receive(scope, frame(1, 100), dropped));
        assertFalse(s.frameRendered(100)); assertEquals(1, e.keyRequests);
        assertEquals(ScreenCastFrameQueue.Offer.ACCEPTED, s.receive(scope, frame(2, 200), accepted));
        assertEquals(State.WAITING_FIRST_FRAME, s.state()); assertTrue(e.receipts.isEmpty());
        assertTrue(s.frameRendered(200)); assertEquals(new Receipt(scope, 2, 200), e.receipts.get(0));
        assertFalse(s.frameRendered(200)); assertEquals(1, e.streaming.size());
    }
    @Test public void revocationAndTimeoutCloseResourcesOnce() {
        Endpoint e = new Endpoint(); ScreenCastSession s = session(Role.SOURCE, e); ready(s);
        clock.addAndGet(5_000); s.tick(); assertEquals(List.of("first_frame_timeout"), e.closed);
        s.close(); s.tick(); assertEquals(1, e.closed.size()); assertFalse(s.offer(frame(1, 100)));
        Endpoint other = new Endpoint(); ScreenCastSession fresh = session(Role.SINK, other); ready(fresh);
        allowed.set(false); fresh.tick(); assertEquals(List.of("screen_permission_revoked"), other.closed);
    }
    @Test public void mismatchedNegotiationAndPrepareTimeoutCannotStart() {
        Endpoint e = new Endpoint(); ScreenCastSession s = session(Role.SOURCE, e);
        assertFalse(s.remotePrepared(scope, new ScreenCastFormat(1280,720,30,1_000_000)));
        assertEquals(List.of("screen_format_mismatch"), e.closed);
        Endpoint other = new Endpoint(); ScreenCastSession pending = session(Role.SINK, other);
        clock.addAndGet(10_000); pending.tick(); assertEquals(List.of("screen_prepare_timeout"), other.closed);
    }
    @Test public void oldStreamsAndAmbiguousPtsCannotClaimSuccess() {
        Endpoint e = new Endpoint(); ScreenCastSession s = session(Role.SINK, e); ready(s);
        Receiver accepted = packet -> ScreenCastFrameQueue.Offer.ACCEPTED;
        assertEquals(ScreenCastFrameQueue.Offer.STALE, s.receive(new Scope("c".repeat(64), 10), frame(1,100), accepted));
        assertFalse(s.frameRendered(100));
        s.receive(scope, frame(1,100), accepted); s.receive(scope, frame(2,100), accepted);
        assertEquals(List.of("ambiguous_frame_timestamp"), e.closed); assertFalse(s.frameRendered(100));
    }
    @Test public void metadataIsBoundedAndKeyRequestsAreRateLimited() {
        Endpoint e = new Endpoint(); ScreenCastSession s = session(Role.SOURCE, e); ready(s);
        for (int i=1; i<=257; i++) assertTrue(s.offer(frame(i,i*100L)));
        assertFalse(s.renderedReceipt(new Receipt(scope,1,100)));
        assertTrue(s.renderedReceipt(new Receipt(scope,257,25700)));
        assertFalse(s.keyFrameRequest(new Scope("d".repeat(64),9),()->e.keyRequests++));
        assertTrue(s.keyFrameRequest(scope,()->e.keyRequests++));
        assertFalse(s.keyFrameRequest(scope,()->e.keyRequests++)); assertEquals(1,e.keyRequests);
        Endpoint sink = new Endpoint(); ScreenCastSession receiver = session(Role.SINK,sink); ready(receiver);
        receiver.keyFrameNeeded(); receiver.keyFrameNeeded(); assertEquals(1,sink.keyRequests);
        clock.addAndGet(500); receiver.keyFrameNeeded(); assertEquals(2,sink.keyRequests);
    }
    @Test public void renderedReceiptsAreBoundedWithoutDelayingFirstPicture() {
        Endpoint e=new Endpoint();ScreenCastSession s=session(Role.SINK,e);ready(s);
        for(int i=1;i<=30;i++){
            s.receive(scope,frame(i,i*100L),p->ScreenCastFrameQueue.Offer.ACCEPTED);
            assertTrue(s.frameRendered(i*100L));clock.addAndGet(33);
        }
        assertEquals(1,e.receipts.size());assertEquals(1,e.receipts.get(0).sequence());
        assertEquals(1,e.streaming.size());
    }
    @Test public void configurationResendKeepsOutstandingRenderEvidence() {
        Endpoint e=new Endpoint();ScreenCastSession s=session(Role.SOURCE,e);ready(s);
        assertTrue(s.offer(frame(1,100)));
        assertTrue(s.offer(new ScreenCastPacket(scope.streamId(),2,0,ScreenCastPacket.CONFIG,new byte[]{1})));
        assertTrue(s.renderedReceipt(new Receipt(scope,1,100)));
    }
}
