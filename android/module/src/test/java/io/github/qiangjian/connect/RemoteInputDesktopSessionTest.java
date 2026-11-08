package io.github.qiangjian.connect;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static io.github.qiangjian.connect.RemoteInputDesktopSession.*;
import static io.github.qiangjian.connect.RemoteInputSession.Result.*;
import static org.junit.Assert.*;

public class RemoteInputDesktopSessionTest {
    private final RemoteInputSession.Scope scope = new RemoteInputSession.Scope("b".repeat(64), 8, 12);
    private final AtomicBoolean allowed = new AtomicBoolean(true);
    private final AtomicLong time = new AtomicLong(100);
    private static final class Native implements Target {
        final List<Mouse> mouse = new ArrayList<>();
        final List<Key> keys = new ArrayList<>();
        final List<Reset> releases = new ArrayList<>();
        boolean focused = true, accept = true;
        int failMouseAt = -1;
        public boolean hasFocus(long epoch) { return focused; }
        public boolean mouse(Mouse event) { mouse.add(event); return accept && mouse.size() != failMouseAt; }
        public boolean key(Key event) { keys.add(event); return accept; }
        public ReleaseResult release(Reset reset) {
            releases.add(reset); return focused ? ReleaseResult.RELEASED : ReleaseResult.FOCUS_CHANGED;
        }
    }
    private RemoteInputDesktopSession session(Native nativeTarget) {
        return new RemoteInputDesktopSession(scope, allowed::get, time::get, nativeTarget);
    }
    @Test public void hoverDragChordAndReleaseUseOneMouseContact() {
        Native n = new Native(); var s = session(n);
        assertEquals(ACCEPTED, s.move(scope, 1, .1f, .2f));
        assertEquals(MouseAction.HOVER_MOVE, n.mouse.get(0).action());
        assertEquals(ACCEPTED, s.button(scope, 2, PRIMARY, true, .1f, .2f));
        time.set(120); assertEquals(ACCEPTED, s.button(scope, 3, SECONDARY, true, .2f, .3f));
        assertEquals(3, n.mouse.get(3).buttons()); assertEquals(MouseAction.BUTTON_PRESS, n.mouse.get(3).action());
        assertEquals(ACCEPTED, s.move(scope, 4, .3f, .4f));
        assertEquals(MouseAction.MOVE, n.mouse.get(4).action()); assertEquals(100, n.mouse.get(4).downTimeMs());
        assertEquals(ACCEPTED, s.button(scope, 5, PRIMARY, false, .3f, .4f));
        assertEquals(SECONDARY, n.mouse.get(5).buttons());
        assertEquals(ACCEPTED, s.button(scope, 6, SECONDARY, false, .3f, .4f));
        assertEquals(MouseAction.BUTTON_RELEASE, n.mouse.get(6).action());
        assertEquals(MouseAction.UP, n.mouse.get(7).action()); assertEquals(0, n.mouse.get(7).buttons());
        s.close(); assertTrue(n.releases.isEmpty());
    }
    @Test public void mouseAndKeyboardCannotReplayAcrossSeparateOperations() {
        Native n = new Native(); var s = session(n);
        assertEquals(ACCEPTED, s.key(scope, 1, 113, true)); // CTRL
        assertEquals(REPLAY, s.move(scope, 1, .5f, .5f));
        assertEquals(ACCEPTED, s.button(scope, 2, PRIMARY, true, .5f, .5f));
        assertEquals(0x3000, n.mouse.get(0).metaState());
        assertEquals(REPLAY, s.key(scope, 2, 113, false));
        s.close(); assertEquals(1, n.releases.size());
        assertEquals(MouseAction.CANCEL, n.releases.get(0).mouse().action());
        Key release = n.releases.get(0).keys().get(0);
        assertEquals(113, release.keyCode()); assertTrue(release.cancelled()); assertFalse(release.down());
    }
    @Test public void repeatedKeysKeepOriginalDownTimeAndModifierChords() {
        Native n = new Native(); var s = session(n);
        assertEquals(ACCEPTED, s.key(scope, 1, 59, true));
        assertEquals(ACCEPTED, s.key(scope, 2, 29, true)); // A
        time.set(200); assertEquals(ACCEPTED, s.key(scope, 3, 29, true));
        Key repeated = n.keys.get(2);
        assertEquals(1, repeated.repeatCount()); assertEquals(100, repeated.downTimeMs()); assertEquals(200, repeated.eventTimeMs());
        assertEquals(0x41, repeated.metaState());
        assertEquals(ACCEPTED, s.key(scope, 4, 29, false));
        assertEquals(ACCEPTED, s.key(scope, 5, 59, false)); assertEquals(0, n.keys.get(4).metaState());
        assertEquals(INVALID_PAYLOAD, s.key(scope, 6, 59, false));
        s.close(); assertEquals(ReleaseResult.NOT_NEEDED, s.releaseResult());
    }
    @Test public void invalidCoordinatesScrollAndButtonTransitionsNeverDispatch() {
        Native n = new Native(); var s = session(n);
        assertEquals(INVALID_PAYLOAD, s.move(scope, 1, Float.NaN, .5f));
        assertEquals(INVALID_PAYLOAD, s.move(scope, 2, 1.01f, .5f));
        assertEquals(INVALID_PAYLOAD, s.button(scope, 3, PRIMARY, false, .5f, .5f));
        assertEquals(INVALID_PAYLOAD, s.button(scope, 4, 3, true, .5f, .5f));
        assertEquals(INVALID_PAYLOAD, s.scroll(scope, 5, .5f, .5f, 0, Float.POSITIVE_INFINITY));
        assertEquals(INVALID_PAYLOAD, s.scroll(scope, 6, .5f, .5f, 33, 0));
        assertEquals(INVALID_PAYLOAD, s.scroll(scope, 7, .5f, .5f, 0, 0));
        assertTrue(n.mouse.isEmpty());
        assertEquals(ACCEPTED, s.scroll(scope, 8, 0, 1, -2, 3));
        Mouse scroll = n.mouse.get(0);
        assertEquals(MouseAction.SCROLL, scroll.action()); assertEquals(-2, scroll.scrollX(), 0); assertEquals(3, scroll.scrollY(), 0);
        assertEquals(REPLAY, s.key(scope, 8, 29, true));
    }
    @Test public void partialNativeButtonFailureCancelsInsteadOfRetryingClick() {
        Native n = new Native(); n.failMouseAt = 2; var s = session(n);
        assertEquals(NATIVE_REJECTED, s.button(scope, 1, SECONDARY, true, .5f, .5f));
        assertEquals(2, n.mouse.size()); assertEquals(1, n.releases.size());
        assertEquals(MouseAction.CANCEL, n.releases.get(0).mouse().action());
        assertEquals(STALE_SESSION, s.button(scope, 2, SECONDARY, true, .5f, .5f));
        s.close(); assertEquals(1, n.releases.size());
    }
    @Test public void failedLastReleaseStillCancelsOpenMouseContact() {
        Native n = new Native(); var s = session(n);
        assertEquals(ACCEPTED, s.button(scope, 1, PRIMARY, true, .5f, .5f));
        n.failMouseAt = 3;
        assertEquals(NATIVE_REJECTED, s.button(scope, 2, PRIMARY, false, .5f, .5f));
        assertEquals(MouseAction.CANCEL, n.releases.get(0).mouse().action());
        assertEquals(ReleaseResult.RELEASED, s.releaseResult());
    }
    @Test public void revocationAndNewFocusReleaseOnlyOldLease() {
        Native n = new Native(); var s = session(n);
        assertEquals(ACCEPTED, s.key(scope, 1, 29, true));
        allowed.set(false);
        assertEquals(DENIED, s.move(scope, 2, .5f, .5f));
        assertEquals(1, n.releases.size()); assertTrue(n.releases.get(0).keys().get(0).cancelled());
        allowed.set(true); n.focused = true; var fresh = session(n);
        assertEquals(ACCEPTED, fresh.key(scope, 1, 30, true));
        n.focused = false;
        assertEquals(NO_FOCUS, fresh.move(scope, 2, .5f, .5f));
        assertEquals(ReleaseResult.FOCUS_CHANGED, fresh.releaseResult());
        n.focused = true; assertEquals(STALE_SESSION, fresh.key(scope, 3, 30, true));
    }
    @Test public void staleScopeAndHeldKeyCapacityAreRejectedBeforeInjection() {
        Native n = new Native(); var s = session(n);
        assertEquals(STALE_SESSION, s.key(new RemoteInputSession.Scope("c".repeat(64), 8, 12), 1, 29, true));
        assertEquals(STALE_FOCUS, s.key(new RemoteInputSession.Scope("b".repeat(64), 8, 13), 1, 29, true));
        for (int i = 0; i < MAX_HELD_KEYS; i++) assertEquals(ACCEPTED, s.key(scope, i + 1, 29 + i, true));
        assertEquals(INVALID_PAYLOAD, s.key(scope, 17, 50, true)); assertEquals(MAX_HELD_KEYS, n.keys.size());
        s.close(); assertEquals(MAX_HELD_KEYS, n.releases.get(0).keys().size());
        assertTrue(n.releases.get(0).keys().stream().allMatch(key -> key.cancelled() && !key.down()));
    }
    @Test public void nativeMetaSnapshotIsPreservedWithoutInventingModifierDowns() {
        Native n = new Native(); var s = session(n);
        assertEquals(ACCEPTED, s.key(scope, 1, 31, true, 0x2000 | 0x100000));
        assertEquals(0x103000, n.keys.get(0).metaState()); // Native Ctrl-left + caps, normalized.
        assertEquals(1, n.keys.size()); // No synthetic CTRL key-down.
        assertEquals(ACCEPTED, s.scroll(scope, 2, .5f, .5f, 0, 1, 0x40));
        assertEquals(0x41, n.mouse.get(0).metaState());
        assertEquals(INVALID_PAYLOAD, s.move(scope, 3, .5f, .5f, 0x80000000));
        assertEquals(1, n.mouse.size());
    }
}
