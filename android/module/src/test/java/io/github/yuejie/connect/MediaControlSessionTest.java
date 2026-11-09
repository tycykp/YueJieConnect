package io.github.yuejie.connect;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class MediaControlSessionTest {
    static final String PKG = "test.synthetic.player";
    static MediaControlSession.Request request(MediaControlSession.Action action, long value, int expected) {
        return new MediaControlSession.Request(action, PKG, "token1", value, expected);
    }
    static class Fake implements MediaControlSession.Backend, MediaControlSession.Clock {
        int state = 2, volume = 4, applyCount, readCount;
        long position, time, actions = 1 | 2 | 4 | 256;
        long queueItem = -1, nextItem = -1, previousItem = -1;
        String token = "token1";
        boolean change = true;
        Runnable onApply = () -> {}, onRead = () -> {};
        public MediaControlSession.State read(MediaControlSession.Request request) {
            readCount++; onRead.run();
            return new MediaControlSession.State(token, state, position, actions, volume, 0, 15, true, false,
                    queueItem, nextItem, previousItem);
        }
        public void apply(MediaControlSession.Request request, MediaControlSession.State before) {
            applyCount++;
            if (change) switch (request.action()) {
                case PLAY -> state = 3;
                case PAUSE -> state = 2;
                case STOP -> state = 1;
                case SEEK -> position = request.value();
                case NEXT, PREVIOUS -> queueItem = request.value();
                case FAST_FORWARD -> state = 4;
                case REWIND -> state = 5;
                case SET_VOLUME, RESTORE_VOLUME -> volume = (int) request.value();
                default -> throw new AssertionError();
            }
            onApply.run();
        }
        public long now() { return time; }
        public void sleep(long ms) { time += ms; }
        MediaControlSession engine() { return new MediaControlSession(this, this); }
    }
    @Test public void noPermissionMeansNoReadOrWrite() {
        Fake f = new Fake();
        assertEquals("stale_session", f.engine().execute(request(MediaControlSession.Action.PLAY, 0, -1), () -> false, () -> false).code());
        assertEquals(0, f.readCount); assertEquals(0, f.applyCount);
    }
    @Test public void queryNeverMutates() {
        Fake f = new Fake();
        var r = f.engine().execute(request(MediaControlSession.Action.QUERY, 0, -1), () -> true, () -> false);
        assertTrue(r.confirmed()); assertFalse(r.dispatched()); assertEquals(0, f.applyCount);
    }
    @Test public void completedPlayRequiresReadback() {
        Fake f = new Fake();
        var r = f.engine().execute(request(MediaControlSession.Action.PLAY, 0, -1), () -> true, () -> false);
        assertTrue(r.confirmed()); assertTrue(r.dispatched()); assertEquals(3, r.state().playback()); assertEquals(2, f.readCount);
    }
    @Test public void acceptedButIgnoredCommandIsNotSuccess() {
        Fake f = new Fake(); f.change = false;
        var r = f.engine().execute(request(MediaControlSession.Action.PLAY, 0, -1), () -> true, () -> false);
        assertEquals("result_unconfirmed", r.code()); assertTrue(r.dispatched()); assertEquals(2000, f.time);
    }
    @Test public void waitsForDelayedState() {
        Fake f = new Fake(); f.change = false;
        f.onRead = () -> { if (f.time >= 120) f.state = 3; };
        assertTrue(f.engine().execute(request(MediaControlSession.Action.PLAY, 0, -1), () -> true, () -> false).confirmed());
        assertEquals(120, f.time);
    }
    @Test public void tokenReplacementPreventsDispatch() {
        Fake f = new Fake(); f.token = "new";
        assertEquals("stale_media_session", f.engine().execute(request(MediaControlSession.Action.PLAY, 0, -1), () -> true, () -> false).code());
        assertEquals(0, f.applyCount);
    }
    @Test public void replacementDuringReadbackCannotConfirmDifferentPlayer() {
        Fake f = new Fake(); f.onApply = () -> f.token = "new";
        var r = f.engine().execute(request(MediaControlSession.Action.PLAY, 0, -1), () -> true, () -> false);
        assertEquals("stale_media_session", r.code()); assertTrue(r.dispatched());
    }
    @Test public void noActionSupportDoesNotDispatch() {
        Fake f = new Fake(); f.actions = 0;
        assertEquals("action_unavailable", f.engine().execute(request(MediaControlSession.Action.PLAY, 0, -1), () -> true, () -> false).code());
        assertEquals(0, f.applyCount);
    }
    @Test public void alreadyPausedDoesNotToggle() {
        Fake f = new Fake();
        assertTrue(f.engine().execute(request(MediaControlSession.Action.PAUSE, 0, -1), () -> true, () -> false).confirmed());
        assertEquals(0, f.applyCount);
    }
    @Test public void preCancelledDoesNothing() {
        Fake f = new Fake();
        assertEquals("cancelled", f.engine().execute(request(MediaControlSession.Action.PLAY, 0, -1), () -> true, () -> true).code());
        assertEquals(0, f.applyCount); assertEquals(0, f.readCount);
    }
    @Test public void cancellationAfterDispatchDoesNotPretendRollback() {
        Fake f = new Fake(); AtomicBoolean cancel = new AtomicBoolean(); f.onApply = () -> cancel.set(true);
        var r = f.engine().execute(request(MediaControlSession.Action.PLAY, 0, -1), () -> true, cancel::get);
        assertEquals("cancelled_after_dispatch", r.code()); assertTrue(r.dispatched()); assertEquals(3, f.state);
    }
    @Test public void revocationDuringQueryDoesNotReportSuccess() {
        Fake f = new Fake(); AtomicBoolean auth = new AtomicBoolean(true); f.onRead = () -> auth.set(false);
        assertEquals("stale_session", f.engine().execute(request(MediaControlSession.Action.QUERY, 0, -1), auth::get, () -> false).code());
    }
    @Test public void restorePreservesNewUserVolume() {
        Fake f = new Fake();
        assertEquals("volume_changed", f.engine().execute(request(MediaControlSession.Action.RESTORE_VOLUME, 2, 3), () -> true, () -> false).code());
        assertEquals(4, f.volume); assertEquals(0, f.applyCount);
    }
    @Test public void restoreChecksAndReadsBack() {
        Fake f = new Fake();
        assertTrue(f.engine().execute(request(MediaControlSession.Action.RESTORE_VOLUME, 2, 4), () -> true, () -> false).confirmed());
        assertEquals(2, f.volume);
    }
    @Test public void clampMustNotPretendRequestedLevelApplied() {
        Fake f = new Fake();
        assertEquals("volume_out_of_range", f.engine().execute(request(MediaControlSession.Action.SET_VOLUME, 20, -1), () -> true, () -> false).code());
        assertEquals(0, f.applyCount);
    }
    @Test public void seekVerifiesPosition() {
        Fake f = new Fake();
        assertTrue(f.engine().execute(request(MediaControlSession.Action.SEEK, 30000, -1), () -> true, () -> false).confirmed());
        assertEquals(30000, f.position);
    }
    @Test public void concurrentCallsRejectedWithoutReading() throws Exception {
        Fake f = new Fake(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        f.onApply = () -> { entered.countDown(); try { assertTrue(release.await(2, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); } };
        MediaControlSession engine = f.engine();
        Thread first = new Thread(() -> engine.execute(request(MediaControlSession.Action.PLAY, 0, -1), () -> true, () -> false));
        first.start(); assertTrue(entered.await(2, TimeUnit.SECONDS));
        try { assertEquals("busy", engine.execute(request(MediaControlSession.Action.QUERY, 0, -1), () -> true, () -> false).code()); }
        finally { release.countDown(); first.join(2000); }
        assertFalse(first.isAlive()); assertEquals(1, f.applyCount);
    }
    @Test public void normalizedVolumeUsesRemoteRangeAndRejectsOverflow() {
        assertEquals(2, MediaControlSession.volumeFromBasisPoints(0, 2, 17));
        assertEquals(17, MediaControlSession.volumeFromBasisPoints(10000, 2, 17));
        assertEquals(9, MediaControlSession.volumeFromBasisPoints(5000, 2, 17));
        assertThrows(IllegalArgumentException.class, () -> MediaControlSession.volumeFromBasisPoints(10001, 0, 15));
        assertThrows(IllegalArgumentException.class, () -> MediaControlSession.volumeFromBasisPoints(1, 2, 2));
    }
    @Test public void invalidRequestsFailBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () -> request(MediaControlSession.Action.SEEK, -1, -1));
        assertThrows(IllegalArgumentException.class, () -> request(MediaControlSession.Action.RESTORE_VOLUME, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new MediaControlSession.Request(MediaControlSession.Action.PLAY, PKG, "", 0, -1));
    }
    @Test public void toggleUsesExecutionTimeStateAndRequiredAction() {
        Fake f = new Fake(); f.state = 3; f.actions = 2;
        assertTrue(f.engine().execute(request(MediaControlSession.Action.TOGGLE, 0, -1), () -> true, () -> false).confirmed());
        assertEquals(2, f.state);
        assertEquals("action_unavailable", f.engine().execute(request(MediaControlSession.Action.TOGGLE, 0, -1), () -> true, () -> false).code());
    }
    @Test public void relativeSeekUsesReceiverPosition() {
        Fake f = new Fake(); f.position = 10_000;
        assertTrue(f.engine().execute(request(MediaControlSession.Action.SEEK_FORWARD, 0, -1), () -> true, () -> false).confirmed());
        assertEquals(40_000, f.position);
        f.position = 5000;
        assertTrue(f.engine().execute(request(MediaControlSession.Action.SEEK_BACKWARD, 0, -1), () -> true, () -> false).confirmed());
        assertEquals(0, f.position);
    }
    @Test public void castVolumePercentIsNotBasisPoints() {
        assertEquals(7, MediaControlSession.volumeFromPercent(50, 15));
        assertEquals(0, MediaControlSession.volumeFromPercent(0, 15));
        assertEquals(15, MediaControlSession.volumeFromPercent(100, 15));
        assertThrows(IllegalArgumentException.class, () -> MediaControlSession.volumeFromPercent(10000, 15));
    }
    @Test public void nextRequiresExactKnownQueueNeighbor() {
        Fake f = new Fake(); f.actions = 32;
        assertEquals("queue_unavailable", f.engine().execute(request(MediaControlSession.Action.NEXT, 0, -1), () -> true, () -> false).code());
        assertEquals(0, f.applyCount);
        f.queueItem = 17; f.nextItem = 23;
        assertTrue(f.engine().execute(request(MediaControlSession.Action.NEXT, 0, -1), () -> true, () -> false).confirmed());
        assertEquals(23, f.queueItem); assertEquals(1, f.applyCount);
    }
    @Test public void previousUsesNativeQueueIdNotIndexOrPosition() {
        Fake f = new Fake(); f.actions = 16; f.queueItem = 23; f.previousItem = 17;
        assertTrue(f.engine().execute(request(MediaControlSession.Action.PREVIOUS, 0, -1), () -> true, () -> false).confirmed());
        assertEquals(17, f.queueItem);
    }
    @Test public void skippingTransientOrDifferentQueueItemCannotConfirmNext() {
        Fake f = new Fake(); f.actions = 32; f.queueItem = 17; f.nextItem = 23;
        f.onApply = () -> { f.queueItem = 99; f.state = 10; };
        assertEquals("result_unconfirmed", f.engine().execute(request(MediaControlSession.Action.NEXT, 0, -1), () -> true, () -> false).code());
        f.time = 0; f.queueItem = 17; f.onApply = () -> f.state = 10;
        assertEquals("result_unconfirmed", f.engine().execute(request(MediaControlSession.Action.NEXT, 0, -1), () -> true, () -> false).code());
    }
    @Test public void fastForwardAndRewindAreNotFixedSeekCommands() {
        Fake f = new Fake(); f.actions = 64 | 8; f.position = 100;
        assertTrue(f.engine().execute(request(MediaControlSession.Action.FAST_FORWARD, 0, -1), () -> true, () -> false).confirmed());
        assertEquals(4, f.state); assertEquals(100, f.position);
        assertTrue(f.engine().execute(request(MediaControlSession.Action.REWIND, 0, -1), () -> true, () -> false).confirmed());
        assertEquals(5, f.state); assertEquals(100, f.position);
    }
    @Test public void ignoredFastForwardRemainsUnconfirmed() {
        Fake f = new Fake(); f.actions = 64; f.change = false;
        assertEquals("result_unconfirmed", f.engine().execute(request(MediaControlSession.Action.FAST_FORWARD, 0, -1), () -> true, () -> false).code());
    }
}
