package io.github.yuejie.connect;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static io.github.yuejie.connect.RemoteInputSession.Result.*;

public class RemoteInputSessionTest {
    private final RemoteInputSession.Scope scope = new RemoteInputSession.Scope("a".repeat(64), 1, 5);
    private static class Target implements RemoteInputSession.Target {
        int calls, cancelled; boolean focus = true, accepted = true;
        public boolean hasFocus(long epoch) { return focus; }
        public boolean commitText(String text) { calls++; return accepted; }
        public boolean key(int key, boolean down) { calls++; return accepted; }
        public void cancel() { cancelled++; }
    }
    @Test public void scopeFocusRevocationAndReplayPreventNativeCall() {
        var target = new Target(); var allowed = new AtomicBoolean(true);
        var session = new RemoteInputSession(scope, allowed::get, target);
        assertEquals(STALE_SESSION, session.text(new RemoteInputSession.Scope("b".repeat(64), 1, 5), 1, "synthetic"));
        assertEquals(STALE_FOCUS, session.text(new RemoteInputSession.Scope("a".repeat(64), 1, 6), 1, "synthetic"));
        assertEquals(ACCEPTED, session.text(scope, 1, "人工测试😀"));
        assertEquals(REPLAY, session.text(scope, 1, "synthetic"));
        target.focus = false; assertEquals(NO_FOCUS, session.key(scope, 2, 66, true));
        target.focus = true; assertEquals(STALE_SESSION, session.text(scope, 3, "synthetic"));
        assertEquals(1, target.calls); assertEquals(1, target.cancelled);
        session.close(); assertEquals(1, target.cancelled);
    }
    @Test public void revocationOrAuthorityFailureCancelsWithoutNativeInput() {
        var target = new Target(); var allowed = new AtomicBoolean(true);
        var session = new RemoteInputSession(scope, allowed::get, target);
        assertEquals(INVALID_PAYLOAD, session.text(null, 1, "synthetic"));
        assertEquals(INVALID_PAYLOAD, session.key(scope, 0, 66, true));
        allowed.set(false); assertEquals(DENIED, session.text(scope, 1, "synthetic"));
        assertEquals(0, target.calls); assertEquals(1, target.cancelled);
        var broken = new RemoteInputSession(scope, () -> { throw new IllegalStateException("authority gone"); }, target);
        assertEquals(DENIED, broken.key(scope, 1, 66, true)); assertEquals(2, target.cancelled);
    }
    @Test public void invalidUnicodeAndOversizedTextAreRejectedWithoutNativeCall() {
        var target = new Target(); var session = new RemoteInputSession(scope, () -> true, target);
        assertEquals(INVALID_PAYLOAD, session.text(scope, 1, "\ud800"));
        assertEquals(INVALID_PAYLOAD, session.text(scope, 2, "中".repeat(3000)));
        assertEquals(INVALID_PAYLOAD, session.text(scope, 3, "a\0b")); assertEquals(0, target.calls);
    }
    @Test public void nativeFailureCannotBeRetriedWithSameSequence() {
        var target = new Target(); target.accepted = false;
        var session = new RemoteInputSession(scope, () -> true, target);
        assertEquals(NATIVE_REJECTED, session.text(scope, 1, "synthetic"));
        target.accepted = true; assertEquals(REPLAY, session.text(scope, 1, "synthetic"));
        assertEquals(1, target.calls);
    }
}
