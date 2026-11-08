package io.github.qiangjian.connect;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import static io.github.qiangjian.connect.TelephonySyncController.*;

public class TelephonySyncControllerTest {
    private final Session session = new Session("synthetic_peer", "a".repeat(64));
    private final AtomicLong clock = new AtomicLong(1000);
    private final AtomicBoolean allowed = new AtomicBoolean(true);
    private final AtomicInteger executions = new AtomicInteger();
    private final TelephonySyncController controller = new TelephonySyncController(session, (s, a) -> allowed.get(), clock::get);
    private String call(State state) { return controller.add(action -> executions.incrementAndGet(), state); }
    private Command command(String id, Action action) { return new Command(session, "request_1", id, controller.snapshot(id).revision(), action); }

    @Test public void waitsForExactNativeStateAndIgnoresOtherCall() {
        String first = call(State.RINGING), other = call(State.RINGING);
        var future = controller.execute(command(first, Action.ANSWER));
        assertEquals(1, executions.get()); assertFalse(future.isDone());
        controller.observe(other, State.ACTIVE); assertFalse(future.isDone());
        controller.observe(first, State.ACTIVE); assertEquals("observed", future.join().code());
    }
    @Test public void missingIdNeverFallsBackToAnotherCall() {
        call(State.RINGING);
        assertEquals("call_missing", controller.execute(new Command(session, "r", "absent", 1, Action.ANSWER)).join().code());
        assertEquals(0, executions.get());
    }
    @Test public void oldRevisionCannotEndReplacedState() {
        String id = call(State.RINGING); Command c = command(id, Action.REJECT);
        controller.observe(id, State.ACTIVE);
        assertEquals("stale_call", controller.execute(c).join().code()); assertEquals(0, executions.get());
    }
    @Test public void duplicateNeverExecutesTwiceAndConflictIsRejected() {
        String id = call(State.RINGING); Command c = command(id, Action.ANSWER);
        var pending = controller.execute(c);
        assertSame(pending, controller.execute(c));
        assertEquals("request_conflict", controller.execute(new Command(session, c.request(), id, 1, Action.REJECT)).join().code());
        controller.observe(id, State.ACTIVE);
        assertSame(pending, controller.execute(c)); assertEquals(1, executions.get());
    }
    @Test public void busyCallAndDifferentSessionCannotExecute() {
        String id = call(State.RINGING); controller.execute(command(id, Action.ANSWER));
        assertEquals("call_busy", controller.execute(new Command(session, "r2", id, 1, Action.REJECT)).join().code());
        assertEquals("session_invalid", controller.execute(new Command(new Session("other", "b".repeat(64)), "r3", id, 1, Action.REJECT)).join().code());
        assertEquals(1, executions.get());
    }
    @Test public void revokeBeforeExecutionAndBeforeObservation() {
        String id = call(State.RINGING); allowed.set(false);
        assertEquals("permission_denied", controller.execute(command(id, Action.ANSWER)).join().code());
        allowed.set(true); var future = controller.execute(command(id, Action.ANSWER)); allowed.set(false);
        controller.observe(id, State.ACTIVE); assertEquals("permission_revoked", future.join().code());
    }
    @Test public void timeoutAndLateEventDoNotTurnIntoSuccess() {
        String id = call(State.RINGING); var future = controller.execute(command(id, Action.ANSWER));
        clock.addAndGet(10_000); controller.tick(); assertEquals("outcome_unknown", future.join().code());
        controller.observe(id, State.ACTIVE); assertEquals("outcome_unknown", future.join().code());
    }
    @Test public void lateEventBeforeTickStillTimesOut() {
        String id = call(State.RINGING); var future = controller.execute(command(id, Action.ANSWER));
        clock.addAndGet(10_000); controller.observe(id, State.ACTIVE);
        assertEquals("outcome_unknown", future.join().code());
    }
    @Test public void removeConfirmsEndButFailsUnansweredCall() {
        String id = call(State.ACTIVE); var future = controller.execute(command(id, Action.END)); controller.remove(id);
        assertEquals("observed", future.join().code()); assertNull(controller.snapshot(id));
        String ringing = call(State.RINGING);
        var answer = controller.execute(new Command(session, "r2", ringing, 1, Action.ANSWER)); controller.remove(ringing);
        assertEquals("call_ended", answer.join().code());
    }
    @Test public void closeDoesNotHangUpAnything() {
        String id = call(State.ACTIVE); var future = controller.execute(command(id, Action.HOLD)); controller.close();
        assertEquals("session_closed", future.join().code()); assertEquals(1, executions.get());
        assertEquals("session_invalid", controller.execute(new Command(session, "r2", id, 1, Action.END)).join().code());
    }
    @Test public void restartDoesNotReuseCallId() {
        String id = call(State.RINGING);
        var replacement = new TelephonySyncController(session, (s, a) -> true, clock::get);
        String second = replacement.add(a -> {}, State.RINGING); assertNotEquals(id, second);
    }
    @Test public void nativeExceptionIsNotObservedSuccess() {
        String id = controller.add(a -> { throw new SecurityException("synthetic"); }, State.RINGING);
        assertEquals("permission_denied", controller.execute(command(id, Action.ANSWER)).join().code());
    }
    @Test public void expiredTaskCannotExecuteAndBudgetShortensObservationWindow() {
        String id = call(State.RINGING); Command c = command(id, Action.ANSWER);
        assertEquals("deadline_expired", controller.execute(c, 0).join().code()); assertEquals(0, executions.get());
        var future = controller.execute(c, 100); clock.addAndGet(100); controller.observe(id, State.ACTIVE);
        assertEquals("outcome_unknown", future.join().code());
    }
    @Test public void cancellationOfSubmittedActionIsUnknownAndDoesNotReplay() {
        String id = call(State.ACTIVE); Command c = command(id, Action.END); var future = controller.execute(c);
        controller.cancel(session, c.request()); assertEquals("outcome_unknown", future.join().code());
        assertSame(future, controller.execute(c)); assertEquals(1, executions.get());
    }
}
