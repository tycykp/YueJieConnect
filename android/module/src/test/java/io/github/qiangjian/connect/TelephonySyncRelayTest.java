package io.github.qiangjian.connect;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.qiangjian.connect.TelephonySyncController.*;

public class TelephonySyncRelayTest {
    private static final Session SESSION = new Session("test-peer", "a".repeat(64));
    private static final class Fixture implements TelephonySyncRelay.NativeUi, Policy {
        long now;
        boolean observe = true, allow = true, audio, failIncoming, failState, failDismiss;
        String token;
        int dismissed, incoming;
        final List<Snapshot> states = new ArrayList<>();
        final List<Command> commands = new ArrayList<>();
        final List<Command> observedGestures = new ArrayList<>();
        final List<CompletableFuture<Result>> replies = new ArrayList<>();
        final TelephonySyncRelay relay = new TelephonySyncRelay(SESSION, this, this, (command, remaining) -> {
            commands.add(command); assertEquals(10_000, remaining);
            CompletableFuture<Result> reply = new CompletableFuture<>(); replies.add(reply); return reply;
        }, () -> now, Runnable::run, (command, observed) -> observedGestures.add(command));
        @Override public boolean observes(Session s) { return observe; }
        @Override public boolean allows(Session s, Action a) { return allow; }
        @Override public boolean audio(Session s, String call) { return audio; }
        @Override public void incoming(String token) { this.token = token; incoming++; if (failIncoming) throw new SecurityException(); }
        @Override public void state(String token, Snapshot state) { if (failState) throw new IllegalStateException(); states.add(state); }
        @Override public void dismiss(String token, String reason) { dismissed++; if (failDismiss) throw new IllegalStateException(); }
        CompletableFuture<TelephonySyncRelay.Receipt> event(long revision, State state) {
            return relay.accept(SESSION, new Snapshot("call_test", revision, state), 10_000);
        }
        void ready() { event(1, State.RINGING); assertTrue(relay.nativeCreated(token)); }
        void reply(int i, long revision, State state) {
            Command c = commands.get(i); replies.get(i).complete(new Result(c.request(), c.call(), "observed", new Snapshot(c.call(), revision, state)));
        }
    }
    @Test public void waitsForActualNativeCreation() {
        Fixture f = new Fixture(); var future = f.event(1, State.RINGING);
        assertFalse(future.isDone()); assertTrue(f.states.isEmpty());
        assertTrue(f.relay.nativeCreated(f.token)); assertEquals("native_created", future.join().code());
        assertEquals(State.RINGING, f.states.get(0).state()); assertFalse(f.relay.nativeCreated(f.token));
    }
    @Test public void rejectsWrongSessionAndMissingConsent() {
        Fixture f = new Fixture(); f.observe = false;
        assertEquals("permission_denied", f.event(1, State.RINGING).join().code());
        assertEquals("session_invalid", f.relay.accept(new Session("other", "b".repeat(64)), new Snapshot("call", 1, State.RINGING), 1).join().code());
        assertEquals(0, f.incoming);
    }
    @Test public void rejectsMalformedAndNonIncomingInitialState() {
        Fixture f = new Fixture();
        assertEquals("invalid_snapshot", f.relay.accept(SESSION, new Snapshot("bad number", 1, State.RINGING), 1).join().code());
        assertEquals("initial_state_invalid", f.event(1, State.ACTIVE).join().code()); assertEquals(0, f.incoming);
    }
    @Test public void nativeFailureIsNotSuccessOrRetry() {
        Fixture f = new Fixture(); f.failIncoming = true;
        assertEquals("permission_denied", f.event(1, State.RINGING).join().code());
        assertFalse(f.relay.nativeCreated(f.token)); f.failIncoming = false;
        assertEquals("permission_denied", f.event(1, State.RINGING).join().code()); assertEquals(1, f.incoming);
    }
    @Test public void exactRevisionAndDuplicateAreEnforced() {
        Fixture f = new Fixture(); f.ready(); f.event(3, State.ACTIVE);
        assertEquals("stale_call", f.event(2, State.RINGING).join().code());
        assertEquals("revision_conflict", f.event(3, State.HOLDING).join().code());
        assertEquals("native_applied", f.event(3, State.ACTIVE).join().code()); assertEquals(2, f.states.size());
    }
    @Test public void closeAndEndedInvalidateLateCreation() {
        Fixture f = new Fixture(); var result = f.event(1, State.RINGING); f.relay.close();
        assertEquals("session_withdrawn", result.join().code()); assertFalse(f.relay.canCreate(f.token));
        assertFalse(f.relay.nativeCreated(f.token)); assertEquals(1, f.dismissed); assertTrue(f.commands.isEmpty());
        Fixture g = new Fixture(); g.event(1, State.RINGING); g.event(2, State.ENDED);
        assertFalse(g.relay.nativeCreated(g.token)); assertEquals("call_ended", g.event(3, State.RINGING).join().code());
    }
    @Test public void creationDeadlineIncludesNativeCallbackDelay() {
        Fixture f = new Fixture(); var result = f.relay.accept(SESSION, new Snapshot("call_test", 1, State.RINGING), 40);
        f.now = 40; assertFalse(f.relay.nativeCreated(f.token));
        assertEquals("creation_timeout", result.join().code()); assertTrue(f.states.isEmpty());
    }
    @Test public void nativeGestureUsesExactCallAndWaitsForObservedState() {
        Fixture f = new Fixture(); f.ready(); assertFalse(f.relay.operate("other", Action.ANSWER));
        assertTrue(f.relay.operate(f.token, Action.ANSWER)); assertFalse(f.relay.operate(f.token, Action.ANSWER));
        Command command = f.commands.get(0); assertEquals(SESSION, command.session());
        assertEquals("call_test", command.call()); assertEquals(1, command.revision());
        assertEquals(State.RINGING, f.states.get(0).state());
        f.reply(0, 2, State.ACTIVE); assertEquals(State.ACTIVE, f.states.get(1).state());
    }
    @Test public void submissionAndUnrelatedRepliesNeverActivateNativeCall() {
        Fixture f = new Fixture(); f.ready(); f.relay.operate(f.token, Action.ANSWER);
        Command c = f.commands.get(0);
        f.replies.get(0).complete(new Result(c.request(), c.call(), "submitted", new Snapshot(c.call(), 2, State.ACTIVE)));
        assertEquals(1, f.states.size());
        f.relay.operate(f.token, Action.ANSWER); c = f.commands.get(1);
        f.replies.get(1).complete(new Result("different", c.call(), "observed", new Snapshot(c.call(), 2, State.ACTIVE)));
        assertEquals(1, f.states.size());
    }
    @Test public void wrongSnapshotAndWrongTargetStateAreRejected() {
        Fixture f = new Fixture(); f.ready(); f.relay.operate(f.token, Action.ANSWER);
        Command c = f.commands.get(0);
        f.replies.get(0).complete(new Result(c.request(), c.call(), "observed", new Snapshot("other", 2, State.ACTIVE)));
        f.relay.operate(f.token, Action.ANSWER); f.reply(1, 2, State.HOLDING);
        assertEquals(1, f.states.size());
    }
    @Test public void olderCompletionCannotRollBackNewerEvent() {
        Fixture f = new Fixture(); f.ready(); f.relay.operate(f.token, Action.ANSWER);
        f.event(3, State.HOLDING); f.reply(0, 2, State.ACTIVE);
        assertEquals(2, f.states.size()); assertEquals(State.HOLDING, f.states.get(1).state());
    }
    @Test public void revokeWithdrawsUiAndBlocksFurtherActions() {
        Fixture f = new Fixture(); f.ready(); f.relay.operate(f.token, Action.ANSWER);
        f.observe = false; f.relay.tick(); f.reply(0, 2, State.ACTIVE);
        assertEquals(1, f.dismissed); assertEquals(1, f.states.size());
        f.observe = true; assertFalse(f.relay.operate(f.token, Action.ANSWER));
    }
    @Test public void controlsRequireSeparateGrant() {
        Fixture f = new Fixture(); f.ready(); f.allow = false;
        assertFalse(f.relay.operate(f.token, Action.ANSWER)); assertTrue(f.commands.isEmpty());
        f.allow = true; assertFalse(f.relay.operate(f.token, Action.HOLD));
        assertTrue(f.relay.operate(f.token, Action.REJECT)); f.reply(0, 2, State.ENDED);
        assertEquals(1, f.dismissed);
    }
    @Test public void expiredOperationDoesNotAutoRetryOrApplyLateResult() {
        Fixture f = new Fixture(); f.ready(); f.relay.operate(f.token, Action.ANSWER);
        f.now = 10_001; f.relay.tick(); f.reply(0, 2, State.ACTIVE);
        assertEquals(1, f.commands.size()); assertEquals(1, f.states.size());
    }
    @Test public void nativeLossOnlyClosesThePresentation() {
        Fixture f = new Fixture(); f.ready(); f.relay.nativeLost(f.token);
        assertTrue(f.commands.isEmpty()); assertEquals(1, f.dismissed); assertFalse(f.relay.operate(f.token, Action.REJECT));
        assertEquals("native_lost", f.event(1, State.RINGING).join().code());
    }
    @Test public void oneNativeCallAndBoundedTombstones() {
        Fixture f = new Fixture(); f.ready();
        assertEquals("relay_busy", f.relay.accept(SESSION, new Snapshot("second", 1, State.RINGING), 10).join().code());
        f.event(2, State.ENDED);
        for (int i = 1; i < 256; i++) f.relay.accept(SESSION, new Snapshot("ended_" + i, 1, State.ENDED), 10).join();
        assertEquals("call_capacity", f.relay.accept(SESSION, new Snapshot("overflow", 1, State.RINGING), 10).join().code());
        assertEquals(1, f.incoming);
    }
    @Test public void nativeStateFailureWithdrawsPresentation() {
        Fixture f = new Fixture(); f.ready(); f.failState = true;
        assertEquals("native_failure", f.event(2, State.ACTIVE).join().code()); assertEquals(1, f.dismissed);
        assertFalse(f.relay.operate(f.token, Action.END));
    }
    @Test public void cleanupFailureIsNotReportedAsNativeEnd() {
        Fixture f = new Fixture(); f.ready(); f.failDismiss = true;
        assertEquals("native_cleanup_failed", f.event(2, State.ENDED).join().code());
        assertEquals("native_cleanup_failed", f.event(2, State.ENDED).join().code());
        assertFalse(f.relay.operate(f.token, Action.END));
    }
    @Test public void asynchronousCreationKeepsEachRequestsRevision() {
        Fixture f = new Fixture(); var ringing = f.event(1, State.RINGING); var active = f.event(2, State.ACTIVE);
        assertTrue(f.relay.nativeCreated(f.token));
        assertEquals(1, ringing.join().revision()); assertEquals("state_superseded", ringing.join().code());
        assertEquals(2, active.join().revision()); assertEquals("native_created", active.join().code());
        assertEquals(State.ACTIVE, f.states.get(0).state());
    }
    @Test public void endBeforeNativeCreationDoesNotAckOldRingingAsEnded() {
        Fixture f = new Fixture(); var ringing = f.event(1, State.RINGING);
        assertEquals("ended", f.event(2, State.ENDED).join().code());
        assertEquals("state_superseded", ringing.join().code()); assertEquals(1, ringing.join().revision());
        assertFalse(f.relay.nativeCreated(f.token));
    }
    @Test public void newerStateCannotExtendOrOutliveItsNativeCreationDeadline() {
        Fixture f = new Fixture(); var original = f.event(1, State.RINGING); f.now = 10;
        var shortTask = f.relay.accept(SESSION, new Snapshot("call_test", 2, State.ACTIVE), 20);
        f.now = 30; assertFalse(f.relay.nativeCreated(f.token));
        assertEquals("creation_timeout", original.join().code()); assertEquals(1, original.join().revision());
        assertEquals("creation_timeout", shortTask.join().code()); assertEquals(2, shortTask.join().revision());
    }
    @Test public void expiredCreationIsWithdrawnBeforeAcceptingAnotherRevision() {
        Fixture f = new Fixture(); var original = f.event(1, State.RINGING); f.now = 10_000;
        assertEquals("call_ended", f.event(2, State.ACTIVE).join().code());
        assertEquals("creation_timeout", original.join().code()); assertFalse(f.relay.nativeCreated(f.token));
    }
    @Test public void audioLeaseRequiresNativeCreationActiveStateAndSeparateCallConsent() {
        Fixture f = new Fixture(); f.audio = true; f.event(1, State.RINGING);
        assertFalse(f.relay.audioActive(SESSION, "call_test")); f.relay.nativeCreated(f.token);
        assertFalse(f.relay.audioActive(SESSION, "call_test")); f.event(2, State.ACTIVE);
        assertTrue(f.relay.audioActive(SESSION, "call_test")); f.audio = false;
        assertFalse(f.relay.audioActive(SESSION, "call_test")); f.audio = true;
        assertFalse(f.relay.audioActive(new Session("other", "b".repeat(64)), "call_test"));
        assertFalse(f.relay.audioActive(SESSION, "other"));
    }
    @Test public void holdEndNativeLossAndCloseWithdrawAudioLease() {
        Fixture f = new Fixture(); f.audio = true; f.ready(); f.event(2, State.ACTIVE);
        assertTrue(f.relay.audioActive(SESSION, "call_test")); f.event(3, State.HOLDING);
        assertFalse(f.relay.audioActive(SESSION, "call_test")); f.event(4, State.ACTIVE);
        assertTrue(f.relay.audioActive(SESSION, "call_test")); f.relay.nativeLost(f.token);
        assertFalse(f.relay.audioActive(SESSION, "call_test"));
        Fixture g = new Fixture(); g.audio = true; g.ready(); g.event(2, State.ACTIVE); g.relay.close();
        assertFalse(g.relay.audioActive(SESSION, "call_test"));
    }
    @Test public void audioApprovalSignalRequiresNativeGestureAndBothObservedNativeStates() {
        Fixture f = new Fixture(); f.ready(); f.relay.operate(f.token, Action.ANSWER);
        assertTrue(f.observedGestures.isEmpty()); f.reply(0, 2, State.ACTIVE);
        assertEquals(1, f.observedGestures.size()); assertEquals(f.commands.get(0), f.observedGestures.get(0));
        Fixture g = new Fixture(); g.ready(); g.event(2, State.ACTIVE);
        assertTrue(g.observedGestures.isEmpty());
    }
    @Test public void nativePresentationFailureCannotApproveCallAudio() {
        Fixture f = new Fixture(); f.ready(); f.relay.operate(f.token, Action.ANSWER); f.failState = true;
        f.reply(0, 2, State.ACTIVE); assertTrue(f.observedGestures.isEmpty());
    }
    @Test public void expiredOrMismatchedNativeGestureCannotApproveCallAudio() {
        Fixture f = new Fixture(); f.ready(); f.relay.operate(f.token, Action.ANSWER); f.now = 10001;
        f.reply(0, 2, State.ACTIVE); assertTrue(f.observedGestures.isEmpty());
        Fixture g = new Fixture(); g.ready(); g.relay.operate(g.token, Action.ANSWER); Command c = g.commands.get(0);
        g.replies.get(0).complete(new Result("other", c.call(), "observed", new Snapshot(c.call(), 2, State.ACTIVE)));
        assertTrue(g.observedGestures.isEmpty());
    }
}
