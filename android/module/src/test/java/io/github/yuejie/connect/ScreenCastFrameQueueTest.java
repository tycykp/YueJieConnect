package io.github.yuejie.connect;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.yuejie.connect.ScreenCastFrameQueue.Offer.*;

public class ScreenCastFrameQueueTest {
    private ScreenCastPacket p(long seq, int flags, int bytes) { return new ScreenCastPacket(9, seq, seq * 1000, flags, new byte[bytes]); }
    @Test public void startsWithConfigurationAndKeyFrameAndRejectsOtherStreams() {
        var q = new ScreenCastFrameQueue(9);
        assertEquals(NEED_KEY_FRAME, q.offer(p(1, 1, 10)));
        assertEquals(ACCEPTED, q.offer(p(2, 2, 10)));
        assertEquals(NEED_KEY_FRAME, q.offer(p(3, 0, 10)));
        assertEquals(ACCEPTED, q.offer(p(4, 1, 10)));
        assertEquals(STALE, q.offer(p(4, 1, 10)));
        assertEquals(STALE, q.offer(new ScreenCastPacket(10, 5, 5000, 1, new byte[10])));
    }
    @Test public void missingReferenceFrameDropsDependentsUntilNextKey() {
        var q = new ScreenCastFrameQueue(9); q.offer(p(1, 2, 10)); q.offer(p(2, 1, 10)); q.poll(); q.poll();
        assertEquals(NEED_KEY_FRAME, q.offer(p(4, 0, 10)));
        assertEquals(NEED_KEY_FRAME, q.offer(p(5, 0, 10)));
        assertEquals(ACCEPTED, q.offer(p(6, 1, 10)));
        assertTrue(q.poll().isConfig()); assertTrue(q.poll().isKey()); assertNull(q.poll());
    }
    @Test public void overflowBoundsMemoryAndRequiresIndependentFrame() {
        var q = new ScreenCastFrameQueue(9); q.offer(p(1, 2, 10)); q.offer(p(2, 1, 1_048_576));
        q.offer(p(3, 0, 1_048_576)); q.offer(p(4, 0, 1_048_576));
        assertEquals(NEED_KEY_FRAME, q.offer(p(5, 0, 1_048_576)));
        assertEquals(1, q.size()); assertTrue(q.needsKeyFrame());
        assertEquals(ACCEPTED, q.offer(p(6, 1, 1_048_576)));
        assertTrue(q.bytes() <= ScreenCastFrameQueue.MAX_BYTES);
    }
    @Test public void timestampRegressionRequiresNewKeyAndCloseIsTerminal() {
        var q = new ScreenCastFrameQueue(9); q.offer(p(1, 2, 10)); q.offer(p(2, 1, 10));
        assertEquals(STALE, q.offer(new ScreenCastPacket(9, 3, 1500, 0, new byte[10])));
        assertTrue(q.needsKeyFrame());
        assertEquals(ACCEPTED, q.offer(p(4, 4, 0))); assertTrue(q.poll().isEnd());
        assertEquals(CLOSED, q.offer(p(5, 1, 10))); q.close(); assertEquals(0, q.bytes());
    }
}
