package io.github.yuejie.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OplusClipboardTasksTest {
    private static final String DIGEST = "a".repeat(64);
    private OplusClipboardTasks tasks() { var value = new OplusClipboardTasks(); value.session("s1"); return value; }

    @Test public void pendingAndCompletedRetriesNeverExecuteTwice() {
        var tasks = tasks();
        var first = tasks.accept("s1", "r1", DIGEST, 100, 0);
        assertEquals(OplusClipboardTasks.Admission.NEW, first.admission());
        assertTrue(tasks.claim(first.permit(), 1));
        assertEquals(OplusClipboardTasks.Admission.DUPLICATE_PENDING, tasks.accept("s1", "r1", DIGEST, 100, 2).admission());
        assertFalse(tasks.claim(first.permit(), 2));
        assertTrue(tasks.complete(first.permit(), null));
        assertEquals(OplusClipboardTasks.Admission.REPLAY_APPLIED, tasks.accept("s1", "r1", DIGEST, 100, 101).admission());
        assertFalse(tasks.claim(first.permit(), 102));
    }
    @Test public void cancellationBeforeClaimPreventsWriteButRunningIsNotRolledBack() {
        var tasks = tasks(); var queued = tasks.accept("s1", "r1", DIGEST, 100, 0);
        assertTrue(tasks.cancel("s1", "r1")); assertFalse(tasks.claim(queued.permit(), 1));
        assertEquals(OplusClipboardTasks.Admission.REPLAY_CANCELLED, tasks.accept("s1", "r1", DIGEST, 100, 2).admission());
        var running = tasks.accept("s1", "r2", DIGEST, 100, 0);
        assertTrue(tasks.claim(running.permit(), 1)); assertFalse(tasks.cancel("s1", "r2"));
        assertTrue(tasks.complete(running.permit(), null));
    }
    @Test public void reconnectAndRevocationInvalidateQueuedWrites() {
        var tasks = tasks(); var old = tasks.accept("s1", "r1", DIGEST, 100, 0);
        tasks.session("s2"); assertFalse(tasks.claim(old.permit(), 1));
        assertThrows(IllegalStateException.class, () -> tasks.accept("s1", "r1", DIGEST, 100, 1));
        var next = tasks.accept("s2", "r1", DIGEST, 100, 1); tasks.session(null);
        assertFalse(tasks.claim(next.permit(), 2));
    }
    @Test public void changedPayloadForSameRequestIsRejected() {
        var tasks = tasks(); tasks.accept("s1", "r1", DIGEST, 100, 0);
        assertThrows(IllegalArgumentException.class, () -> tasks.accept("s1", "r1", "b".repeat(64), 100, 1));
    }
    @Test public void deadlineIsCheckedAtExecutionNotJustAtQueueTime() {
        var tasks = tasks(); var first = tasks.accept("s1", "r1", DIGEST, 100, 0);
        assertFalse(tasks.claim(first.permit(), 100)); assertEquals("request_timeout", tasks.error(first.permit()));
        assertThrows(IllegalArgumentException.class, () -> tasks.accept("s1", "r2", DIGEST, 100, 100));
    }
    @Test public void terminalFailureIsNotRetriedAsAnotherWrite() {
        var tasks = tasks(); var first = tasks.accept("s1", "r1", DIGEST, 100, 0);
        assertTrue(tasks.claim(first.permit(), 1)); assertTrue(tasks.complete(first.permit(), "permission_denied"));
        var replay = tasks.accept("s1", "r1", DIGEST, 100, 2);
        assertEquals(OplusClipboardTasks.Admission.REPLAY_FAILED, replay.admission());
        assertEquals("permission_denied", replay.error());
    }
    @Test public void capacityRefusesNewWritesWithoutEvictingReplayProtection() {
        var tasks = tasks();
        for (int i = 0; i < 256; i++) {
            var accepted = tasks.accept("s1", "r" + i, DIGEST, 100, 0);
            assertTrue(tasks.claim(accepted.permit(), 1));
            assertTrue(tasks.complete(accepted.permit(), null));
        }
        assertThrows(IllegalStateException.class, () -> tasks.accept("s1", "overflow", DIGEST, 100, 2));
        assertEquals(OplusClipboardTasks.Admission.REPLAY_APPLIED, tasks.accept("s1", "r0", DIGEST, 100, 2).admission());
        tasks.session("s2");
        assertEquals(OplusClipboardTasks.Admission.NEW, tasks.accept("s2", "new", DIGEST, 100, 2).admission());
    }
}
