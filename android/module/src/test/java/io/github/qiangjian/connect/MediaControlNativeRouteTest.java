package io.github.qiangjian.connect;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.Test;

public class MediaControlNativeRouteTest {
    static final String PKG = "test.synthetic.player";
    static final String SESSION = "a".repeat(64);
    static class Harness implements MediaControlNativeRoute.Transport {
        record Call(String id, MediaControlSession.Request request, Consumer<MediaControlSession.Result> callback) { }
        final Object peer = new Object();
        final AtomicBoolean active = new AtomicBoolean(true);
        final ArrayList<Call> calls = new ArrayList<>();
        final ArrayList<String> cancelled = new ArrayList<>();
        long now;
        final MediaControlNativeRoute route = new MediaControlNativeRoute(this, () -> now);
        final MediaControlNativeRoute.Scope scope = new MediaControlNativeRoute.Scope("projection1", SESSION, "native-peer", 7, 0);
        void bind(String pkg) { route.bind(scope, peer, pkg, active::get); }
        public void submit(MediaControlNativeRoute.Scope scope, String id, MediaControlSession.Request request,
                Consumer<MediaControlSession.Result> completion) { calls.add(new Call(id, request, completion)); }
        public void cancel(MediaControlNativeRoute.Scope scope, String id) { cancelled.add(id); }
        void reply(int i, int volume, String token, boolean dispatched) {
            calls.get(i).callback.accept(new MediaControlSession.Result("ok", dispatched,
                    new MediaControlSession.State(token, 2, 10_000, 511, volume, 0, 15, true, false)));
        }
    }
    @Test public void discoveryOrDifferentNativeObjectCannotControlVolume() {
        Harness h = new Harness();
        assertEquals("cast_inactive", h.route.volume(h.peer, 50).join().code());
        h.bind("");
        assertEquals("cast_inactive", h.route.volume(new Object(), 50).join().code());
        assertTrue(h.calls.isEmpty());
    }
    @Test public void volumeQueriesRemoteRangeThenUsesConditionalAbsoluteLevel() {
        Harness h = new Harness(); h.bind("");
        var future = h.route.volume(h.peer, 50);
        assertEquals(MediaControlSession.Action.VOLUME_QUERY, h.calls.get(0).request.action());
        h.reply(0, 4, "", false);
        var set = h.calls.get(1).request;
        assertEquals(MediaControlSession.Action.SET_VOLUME, set.action()); assertEquals(7, set.value()); assertEquals(4, set.expectedVolume());
        assertFalse(future.isDone()); h.reply(1, 7, "", true);
        assertTrue(future.join().confirmed());
    }
    @Test public void revokedBetweenQueryAndSetNeverMutates() {
        Harness h = new Harness(); h.bind(""); var future = h.route.volume(h.peer, 50);
        h.active.set(false); h.reply(0, 4, "", false);
        assertEquals("cast_inactive", future.join().code()); assertEquals(1, h.calls.size());
        assertNull(future.join().state());
    }
    @Test public void revokedAfterSubmitCancelsAndNeverClaimsRollback() {
        Harness h = new Harness(); h.bind(""); var future = h.route.volume(h.peer, 50);
        h.reply(0, 4, "", false); h.route.revoke();
        assertEquals(h.calls.get(1).id, h.cancelled.get(0));
        assertEquals("cast_inactive", future.join().code()); assertTrue(future.join().dispatched());
        h.reply(1, 7, "", true); assertNull(future.join().state());
    }
    @Test public void lateOldQueryCannotUseReplacementCast() {
        Harness h = new Harness(); h.bind(""); var old = h.route.volume(h.peer, 50);
        h.route.bind(new MediaControlNativeRoute.Scope("projection1", SESSION, "native-peer", 8, 0), h.peer, "", h.active::get);
        var current = h.route.volume(h.peer, 60);
        h.reply(0, 4, "", false); assertEquals(2, h.calls.size()); assertFalse(current.isDone());
        assertEquals("cast_inactive", old.join().code());
        h.reply(1, 4, "", false); h.reply(2, 9, "", true); assertTrue(current.join().confirmed());
    }
    @Test public void duplicateQueryCallbackCannotDispatchTwice() {
        Harness h = new Harness(); h.bind(""); h.route.volume(h.peer, 50);
        h.reply(0, 4, "", false); h.reply(0, 4, "", false); assertEquals(2, h.calls.size());
    }
    @Test public void wrongReadbackIsNeverConsumedAsSuccess() {
        Harness h = new Harness(); h.bind(""); var result = h.route.volume(h.peer, 50);
        h.reply(0, 4, "", false); h.reply(1, 6, "", true);
        assertEquals("invalid_result", result.join().code()); assertNull(result.join().state());
    }
    @Test public void mediaRequiresNativeSelectedPackageAndQueryToken() {
        Harness h = new Harness(); h.bind("");
        assertEquals("media_target_unbound", h.route.control(h.peer, MediaControlSession.Action.PLAY, 0).join().code());
        h.route.bind(new MediaControlNativeRoute.Scope("projection1", SESSION, "native-peer", 8, 0), h.peer, PKG, h.active::get);
        var result = h.route.control(h.peer, MediaControlSession.Action.PLAY, 0);
        assertEquals(PKG, h.calls.get(0).request.packageName());
        h.reply(0, 4, "token-actual", false);
        assertEquals("token-actual", h.calls.get(1).request.token());
        h.reply(1, 4, "different-token", true); assertEquals("invalid_result", result.join().code());
    }
    @Test public void busyAndInvalidVolumeDoNotQueueNativeMutations() {
        Harness h = new Harness(); h.bind(""); h.route.volume(h.peer, 50);
        assertEquals("busy", h.route.volume(h.peer, 60).join().code());
        assertEquals("invalid_volume", h.route.volume(h.peer, 10000).join().code()); assertEquals(1, h.calls.size());
    }
    @Test public void noReplyDeadlineCancelsAndCompletes() {
        Harness h = new Harness(); h.bind(""); var result = h.route.volume(h.peer, 50);
        h.now = 15_000; h.route.tick(); assertEquals("cast_inactive", result.join().code());
        assertEquals(1, h.cancelled.size());
    }
    @Test public void queryThatClaimsMutationOrNoStateIsRejected() {
        Harness h = new Harness(); h.bind(""); var result = h.route.volume(h.peer, 50);
        h.reply(0, 4, "", true); assertEquals("invalid_result", result.join().code()); assertEquals(1, h.calls.size());
        Harness media = new Harness(); media.bind(PKG);
        var query = media.route.control(media.peer, MediaControlSession.Action.QUERY, 0);
        media.reply(0, 4, "", false); assertEquals("invalid_result", query.join().code());
    }
    @Test public void nativePeerScopeCannotBeGuessedFromNetworkAddress() {
        assertThrows(IllegalArgumentException.class, () -> new MediaControlNativeRoute.Scope("projection", "old-session", "192.0.2.1", 7, 0));
        Harness h = new Harness(); h.active.set(false);
        assertThrows(IllegalStateException.class, () -> h.bind(""));
    }
    @Test public void unresolvedRemoteErrorAfterMutationSubmissionRemainsAttempted() {
        Harness h = new Harness(); h.bind(""); var result = h.route.volume(h.peer, 50);
        h.reply(0, 4, "", false);
        h.calls.get(1).callback.accept(new MediaControlSession.Result("stale_session", false, null));
        assertTrue(result.join().dispatched()); assertEquals("stale_session", result.join().code());
    }
    @Test public void toggleIsConfirmedAgainstReceiversDispatchTimeState() {
        Harness h = new Harness(); h.bind(PKG); var result = h.route.control(h.peer, MediaControlSession.Action.TOGGLE, 0);
        h.reply(0, 4, "token-actual", false);
        // Receiver was playing by dispatch time, so its actual toggle correctly paused it again.
        h.reply(1, 4, "token-actual", true); assertTrue(result.join().confirmed());
    }
    @Test public void revokedCastIdentityCannotBeRegrantedToANewLease() {
        Harness h = new Harness(); h.bind(""); h.route.revoke();
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> h.bind(""));
        assertEquals("media_scope_reused", error.getMessage());
        assertEquals("cast_inactive", h.route.volume(h.peer, 50).join().code());
    }
    @Test public void nativeVolumeQueryDoesNotDispatchASet() {
        Harness h = new Harness(); h.bind(""); var query = h.route.volumeQuery(h.peer);
        h.reply(0, 4, "", false); assertTrue(query.join().confirmed()); assertEquals(1, h.calls.size());
    }
    @Test public void systemVolumeUsesExactRemoteStepWithoutPercentRounding() {
        Harness h = new Harness(); h.bind(""); var command = h.route.volumeAbsolute(h.peer, 6);
        h.reply(0, 5, "", false);
        assertEquals(6, h.calls.get(1).request.value()); assertEquals(5, h.calls.get(1).request.expectedVolume());
        h.reply(1, 6, "", true); assertTrue(command.join().confirmed());
    }
    @Test public void systemVolumeAdjustUsesFreshRemoteReadbackRatherThanOldUi() {
        Harness h = new Harness(); h.bind(""); var command = h.route.volumeAdjust(h.peer, 1);
        h.reply(0, 8, "", false);
        assertEquals(9, h.calls.get(1).request.value()); assertEquals(8, h.calls.get(1).request.expectedVolume());
        h.reply(1, 9, "", true); assertTrue(command.join().confirmed());
    }
    @Test public void systemVolumeCannotGoPastActualRemoteMaximum() {
        Harness h = new Harness(); h.bind(""); var command = h.route.volumeAdjust(h.peer, 1);
        h.reply(0, 15, "", false); assertEquals(15, h.calls.get(1).request.value());
        h.reply(1, 15, "", false); assertTrue(command.join().confirmed()); assertFalse(command.join().dispatched());
    }
    @Test public void outOfRangeAbsoluteVolumeIsRejectedBeforeMutation() {
        Harness h = new Harness(); h.bind(""); var command = h.route.volumeAbsolute(h.peer, 16);
        h.reply(0, 8, "", false); assertEquals("volume_out_of_range", command.join().code()); assertEquals(1, h.calls.size());
    }
    @Test public void invalidSystemVolumeDirectionDoesNotCreateARequest() {
        Harness h = new Harness(); h.bind(""); assertEquals("invalid_volume", h.route.volumeAdjust(h.peer, 0).join().code());
        assertEquals("invalid_volume", h.route.volumeAdjust(h.peer, 100).join().code()); assertTrue(h.calls.isEmpty());
    }
    @Test public void userCommandCanCancelAReadWithoutRevokingItsActualCast() {
        Harness h = new Harness(); h.bind(""); var query = h.route.volumeQuery(h.peer);
        assertTrue(h.route.cancelQuery()); assertEquals("cancelled", query.join().code()); assertFalse(query.join().dispatched());
        var command = h.route.volumeAbsolute(h.peer, 6); h.reply(1, 5, "", false); h.reply(2, 6, "", true);
        assertTrue(command.join().confirmed()); assertEquals(h.calls.get(0).id, h.cancelled.get(0));
    }
    @Test public void aLateCancelledReadCannotBecomeAnotherCommandsBeforeState() {
        Harness h = new Harness(); h.bind(""); h.route.volumeQuery(h.peer); h.route.cancelQuery();
        var command = h.route.volumeAbsolute(h.peer, 6); h.reply(0, 15, "", false);
        assertEquals(2, h.calls.size()); assertFalse(command.isDone());
        h.reply(1, 5, "", false); assertEquals(5, h.calls.get(2).request.expectedVolume());
        h.reply(2, 6, "", true); assertTrue(command.join().confirmed());
    }
    @Test public void mutationIntentCannotBePreemptedDuringItsBeforeStateRead() {
        Harness h = new Harness(); h.bind(""); var command = h.route.volumeAbsolute(h.peer, 6);
        assertFalse(h.route.cancelQuery()); assertFalse(command.isDone()); assertTrue(h.cancelled.isEmpty());
        h.reply(0, 5, "", false); assertFalse(h.route.cancelQuery()); h.reply(1, 6, "", true); assertTrue(command.join().confirmed());
    }
}
