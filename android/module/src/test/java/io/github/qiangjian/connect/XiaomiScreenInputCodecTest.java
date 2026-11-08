package io.github.qiangjian.connect;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.qiangjian.connect.RemoteInputSession.Result.*;

public class XiaomiScreenInputCodecTest {
    public static class Peer { public String id = "synthetic-peer"; public String h() { return id; } }
    public static class Mouse {
        public int screenId = 3, action = 3, x = 50, y = 25, metaInfo;
        public float xAxis, yAxis;
    }
    public static class Key {
        public int screenId = 3, keyCode = 31, metaInfo;
        public boolean down = true;
        public String text;
    }
    private final RemoteInputSession.Scope scope = new RemoteInputSession.Scope("d".repeat(64), 11, 22);
    private final AtomicBoolean allowed = new AtomicBoolean(true);
    private XiaomiScreenInputCodec codec(Peer peer) throws Exception {
        return new XiaomiScreenInputCodec(Peer.class, Mouse.class, Key.class, peer, peer.id,
                3, 101, 51, scope, allowed::get);
    }
    @Test public void recyclableMessageBecomesImmutableNormalizedSnapshot() throws Exception {
        Peer peer = new Peer(); var codec = codec(peer); Mouse mouse = new Mouse();
        var event = codec.snapshot(peer, mouse);
        mouse.x = 100; mouse.y = 50; mouse.action = 1;
        assertEquals(XiaomiScreenInputCodec.Kind.MOVE, event.kind());
        assertEquals(.5f, event.x(), 0); assertEquals(.5f, event.y(), 0);
        assertEquals(scope, codec.scope());
        mouse.action = 6; mouse.xAxis = -1; mouse.yAxis = 2;
        var scroll = codec.snapshot(peer, mouse);
        assertEquals(1, scroll.x(), 0); assertEquals(1, scroll.y(), 0);
        assertEquals(-1, scroll.horizontal(), 0); assertEquals(2, scroll.vertical(), 0);
    }
    @Test public void equalPeerNameDoesNotReplaceNativeObjectOrScreenIdentity() throws Exception {
        Peer peer = new Peer(); var codec = codec(peer); Mouse mouse = new Mouse();
        assertThrows(IllegalStateException.class, () -> codec.snapshot(new Peer(), mouse));
        mouse.screenId = 4; assertThrows(IllegalStateException.class, () -> codec.snapshot(peer, mouse));
        mouse.screenId = 3; peer.id = "replaced-native-peer";
        assertFalse(codec.current()); assertThrows(IllegalStateException.class, () -> codec.snapshot(peer, mouse));
    }
    @Test public void revocationAndInvalidBoundsDoNotCreateExecutableEvents() throws Exception {
        Peer peer = new Peer(); var codec = codec(peer); Mouse mouse = new Mouse();
        mouse.x = 101; assertThrows(IllegalArgumentException.class, () -> codec.snapshot(peer, mouse));
        mouse.x = 0; mouse.action = 0;
        assertThrows(IllegalArgumentException.class, () -> codec.snapshot(peer, mouse));
        mouse.action = 3; allowed.set(false);
        assertThrows(IllegalStateException.class, () -> codec.snapshot(peer, mouse));
        allowed.set(true); codec.close();
        assertThrows(IllegalStateException.class, () -> codec.snapshot(peer, mouse));
    }
    @Test public void nativeKeyMetaReachesSessionButImeTextNeedsItsOwnLease() throws Exception {
        Peer peer = new Peer(); var codec = codec(peer); Key key = new Key();
        key.text = "synthetic IME text"; assertNull(codec.snapshot(peer, key));
        key.text = null; key.metaInfo = 0x2000;
        var event = codec.snapshot(peer, key);
        final RemoteInputDesktopSession.Key[] received = {null};
        var target = new RemoteInputDesktopSession.Target() {
            public boolean hasFocus(long epoch) { return epoch == 22; }
            public boolean mouse(RemoteInputDesktopSession.Mouse event) { return true; }
            public boolean key(RemoteInputDesktopSession.Key event) { received[0] = event; return true; }
            public RemoteInputDesktopSession.ReleaseResult release(RemoteInputDesktopSession.Reset reset) {
                return RemoteInputDesktopSession.ReleaseResult.RELEASED;
            }
        };
        var session = new RemoteInputDesktopSession(scope, allowed::get, () -> 100, target);
        assertEquals(ACCEPTED, event.apply(session, scope, 1));
        assertEquals(0x3000, received[0].metaState()); assertEquals(31, received[0].keyCode());
        assertEquals(REPLAY, event.apply(session, scope, 1));
        assertEquals("NativeDesktopInput(KEY)", event.toString());
    }
    @Test public void nativeImeTextSnapshotUsesIndependentRealFocusLease() throws Exception {
        Peer peer = new Peer(); var codec = codec(peer); Key key = new Key();
        key.text = "synthetic \u4e2d\u6587 \ud83d\ude42";
        var text = codec.snapshotText(peer, key);
        key.text = "recycled";
        var imeScope = new RemoteInputSession.Scope(scope.sessionId(), scope.streamId(), 99);
        String[] accepted = {null};
        var ime = new RemoteInputSession(imeScope, allowed::get, new RemoteInputSession.Target() {
            public boolean hasFocus(long epoch) { return epoch == 99; }
            public boolean commitText(String value) { accepted[0] = value; return true; }
            public boolean key(int code, boolean down) { return false; }
            public void cancel() { }
        });
        assertEquals(STALE_FOCUS, text.apply(ime, scope, 1));
        assertNull(accepted[0]);
        assertEquals(ACCEPTED, text.apply(ime, imeScope, 1));
        assertEquals("synthetic \u4e2d\u6587 \ud83d\ude42", accepted[0]);
        assertEquals("NativeImeText", text.toString());
        assertEquals(REPLAY, text.apply(ime, imeScope, 1));
    }
    @Test public void imeSnapshotRejectsInvalidUnicodeAndByteBudgetBeforeDispatch() throws Exception {
        Peer peer = new Peer(); var codec = codec(peer); Key key = new Key();
        for (String text : new String[]{"\ud800", "\udc00", "a\0b", "\u4e2d".repeat(2731), "a".repeat(8193)}) {
            key.text = text;
            assertThrows(IllegalArgumentException.class, () -> codec.snapshotText(peer, key));
        }
        key.text = "a".repeat(8192); assertEquals(8192, codec.snapshotText(peer, key).text().length());
        key.text = "synthetic"; key.screenId = 4;
        assertThrows(IllegalStateException.class, () -> codec.snapshotText(peer, key));
        key.screenId = 3; allowed.set(false);
        assertThrows(IllegalStateException.class, () -> codec.snapshotText(peer, key));
    }
}
