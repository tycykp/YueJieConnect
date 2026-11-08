package io.github.qiangjian.connect;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.qiangjian.connect.TelephonySyncController.*;
import static io.github.qiangjian.connect.TelephonySyncWire.*;

public class TelephonySyncEndpointTest {
    static final Session SESSION = new Session("synthetic-peer", "a".repeat(64));
    static final class Fixture implements Policy, TelephonySyncRelay.NativeUi {
        long now; boolean ready = true, allow = true; int actions, closes; String token;
        final TelephonySyncController controller = new TelephonySyncController(SESSION, this, () -> now);
        final String call = controller.add(action -> actions++, State.RINGING);
        final TelephonySyncRelay relay = new TelephonySyncRelay(SESSION, this, this,
                (c, r) -> controller.execute(c, r), () -> now, Runnable::run);
        final TelephonySyncEndpoint endpoint = new TelephonySyncEndpoint(SESSION, new TelephonySyncEndpoint.Calls() {
            public CompletableFuture<Result> execute(Command c, long deadline) { return controller.execute(c, deadline - now); }
            public void cancel(Session s, String r) { controller.cancel(s, r); }
        }, new TelephonySyncEndpoint.States() {
            public CompletableFuture<TelephonySyncRelay.Receipt> accept(Session s, Snapshot snapshot, long deadline) { return relay.accept(s, snapshot, deadline - now); }
            public void cancel(Session s, String call, long revision) { relay.cancelPending(s, call, revision); }
        }, null, () -> ready, () -> now, () -> { closes++; controller.close(); relay.close(); });
        @Override public boolean observes(Session s) { return allow; }
        @Override public boolean allows(Session s, Action a) { return allow; }
        @Override public void incoming(String token) { this.token = token; }
        @Override public void state(String token, Snapshot state) {}
        @Override public void dismiss(String token, String reason) {}
        Map<String, Object> answer() { return Map.of("call_id", call, "revision", 1, "action", "answer"); }
        CompletableFuture<Map<String, Object>> answer(String request) { return endpoint.execute(SESSION, CALL_CONTROL, request, answer(), 1000); }
        CompletableFuture<Map<String, Object>> ring(String request, long revision, String state) {
            return endpoint.execute(SESSION, CALL_STATE, request, Map.of("call_id", "synthetic_remote_call", "revision", revision, "state", state), 1000);
        }
    }
    static String error(CompletableFuture<?> future) {
        try { future.join(); fail("unexpected native success"); return ""; }
        catch (java.util.concurrent.CompletionException expected) { return ((Failure) expected.getCause()).code; }
    }
    @Test public void controlCompletesOnlyAfterTheActualCallStateChanges() {
        Fixture f = new Fixture(); var result = f.answer("control_1");
        assertFalse(result.isDone()); assertEquals(1, f.actions);
        f.controller.observe(f.call, State.ACTIVE);
        assertEquals(Map.of("call_id", f.call, "revision", 2L, "state", "active", "observed", true), result.join());
    }
    @Test public void replaysNeverRepeatNativeSideEffectsAndConflictsFail() {
        Fixture f = new Fixture(); var first = f.answer("same"); assertSame(first, f.answer("same"));
        assertEquals("request_conflict", error(f.endpoint.execute(SESSION, CALL_CONTROL, "same",
                Map.of("call_id", f.call, "revision", 1, "action", "reject"), 1000)));
        f.controller.observe(f.call, State.ACTIVE); assertSame(first, f.answer("same")); assertEquals(1, f.actions);
    }
    @Test public void cancellationIsUnknownAndCannotBeChangedByLateNativeResult() {
        Fixture f = new Fixture(); var result = f.answer("cancelled_control"); f.endpoint.cancel(SESSION, "cancelled_control");
        assertEquals("outcome_unknown", error(result)); f.controller.observe(f.call, State.ACTIVE);
        assertEquals("outcome_unknown", error(result)); assertSame(result, f.answer("cancelled_control")); assertEquals(1, f.actions);
    }
    @Test public void foreignSessionCannotExecuteOrCancelTheNativeTask() {
        Fixture f = new Fixture(); Session other = new Session("other", "b".repeat(64));
        assertEquals("session_invalid", error(f.endpoint.execute(other, CALL_CONTROL, "bad", f.answer(), 1000)));
        var result = f.answer("good"); f.endpoint.cancel(other, "good"); assertFalse(result.isDone()); assertEquals(1, f.actions);
    }
    @Test public void queueDeadlineIncludesNativeWaitAndRejectsLateSuccess() {
        Fixture f = new Fixture(); var result = f.answer("deadline"); f.now = 1000; f.endpoint.tick();
        assertEquals("outcome_unknown", error(result)); f.controller.observe(f.call, State.ACTIVE);
        assertEquals("outcome_unknown", error(result));
        assertEquals("deadline_expired", error(f.endpoint.execute(SESSION, CALL_CONTROL, "expired", f.answer(), 1000)));
    }
    @Test public void relayReceiptWaitsForTheActualNativeConnection() {
        Fixture f = new Fixture(); var result = f.ring("native_creation", 1, "ringing"); assertFalse(result.isDone());
        assertTrue(f.relay.nativeCreated(f.token));
        assertEquals(Map.of("call_id", "synthetic_remote_call", "revision", 1L, "effect", "created"), result.join());
    }
    @Test public void coalescedStateNeverMisattributesOldRpcRevision() {
        Fixture f = new Fixture(); var old = f.ring("ringing", 1, "ringing"); var active = f.ring("active", 2, "active");
        f.relay.nativeCreated(f.token); assertEquals("state_superseded", error(old)); assertEquals(2L, active.join().get("revision"));
    }
    @Test public void cancelBeforeCreationPreventsLateNativeRing() {
        Fixture f = new Fixture(); var result = f.ring("cancel_ring", 1, "ringing"); f.endpoint.cancel(SESSION, "cancel_ring");
        assertEquals("outcome_unknown", error(result)); assertFalse(f.relay.nativeCreated(f.token));
    }
    @Test public void withdrawalPermanentlyClosesThisNativeSession() {
        Fixture f = new Fixture(); var result = f.answer("withdraw"); f.ready = false; assertTrue(f.endpoint.operations().isEmpty());
        assertEquals("session_invalid", error(result)); assertEquals(1, f.closes); f.ready = true;
        assertEquals("session_invalid", error(f.answer("new"))); assertEquals(1, f.actions);
    }
    @Test public void absentSmsNativeConsumerIsNeverAdvertised() {
        Fixture f = new Fixture(); assertFalse(f.endpoint.operations().contains(SMS_STATUS));
        assertEquals("native_unavailable", error(f.endpoint.execute(SESSION, SMS_STATUS, "sms",
                Map.of("message_id", "d".repeat(32), "revision", 1, "status", 1), 1000)));
    }
    @Test public void malformedTypesFieldsAndProviderRowsNeverReachNativeControl() {
        Fixture f = new Fixture();
        for (Map<String, Object> raw : java.util.List.of(
                Map.<String, Object>of("call_id", f.call, "revision", 1.0, "action", "answer"),
                Map.<String, Object>of("call_id", f.call, "revision", "1", "action", "answer"),
                Map.<String, Object>of("call_id", f.call, "revision", 1, "action", "dial"),
                Map.<String, Object>of("call_id", f.call, "revision", 1, "action", "answer", "phone_number", "synthetic"))) {
            assertEquals("invalid_payload", error(f.endpoint.execute(SESSION, CALL_CONTROL, "invalid_" + raw.hashCode(), raw, 1000)));
        }
        assertEquals(0, f.actions);
    }
    @Test public void receiptMustMatchExactCallRevisionAndObservedTargetState() {
        Fixture f = new Fixture(); Command command = control(SESSION, "request", f.answer());
        for (Result wrong : java.util.List.of(
                new Result("other", f.call, "observed", new Snapshot(f.call, 2, State.ACTIVE)),
                new Result("request", "other", "observed", new Snapshot("other", 2, State.ACTIVE)),
                new Result("request", f.call, "observed", new Snapshot(f.call, 1, State.ACTIVE)),
                new Result("request", f.call, "observed", new Snapshot(f.call, 2, State.HOLDING))))
            assertThrows(Failure.class, () -> controlReceipt(command, wrong));
    }
    @Test public void unknownNativeErrorIsRedactedAndNotSuccess() {
        Fixture f = new Fixture(); Command c = control(SESSION, "request", f.answer());
        Failure failure = assertThrows(Failure.class, () -> controlReceipt(c, new Result(c.request(), c.call(), "private native error", null)));
        assertEquals("remote_error", failure.code);
        assertEquals("remote_error", failure.getMessage());
    }
    @Test public void wireRoundTripPreservesStrictIdentityAndIntegerPrecision() {
        Fixture f = new Fixture(); Snapshot state = new Snapshot(f.call, Long.MAX_VALUE, State.ENDED);
        assertEquals(state, state(state(state)));
        assertThrows(Failure.class, () -> state(new Snapshot(f.call, 1, State.DIALING)));
        Command c = control(SESSION, "request", f.answer());
        Result actual = new Result(c.request(), c.call(), "observed", new Snapshot(c.call(), 2, State.ACTIVE));
        assertEquals(actual, controlReceipt(c, controlReceipt(c, actual)));
    }
}
