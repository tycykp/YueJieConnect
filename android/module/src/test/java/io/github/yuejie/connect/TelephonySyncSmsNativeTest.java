package io.github.yuejie.connect;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.yuejie.connect.TelephonySyncController.Session;
import static io.github.yuejie.connect.TelephonySyncSmsNative.*;

public class TelephonySyncSmsNativeTest {
    @Test public void malformedNativeIdentifierCannotReachOemInterpolatedSelection() {
        for (String id : new String[] {"native' OR 1=1 --", "native\nrow", "native\0row", "native\u007frow"})
            assertThrows(IllegalArgumentException.class, () -> new TelephonySyncSmsNative.Target(id, "12"));
    }
    @Test public void opaqueNativeIdentifierIsKeptLocalAndRedacted() {
        var target = new TelephonySyncSmsNative.Target("synthetic-identifier:12", "12");
        assertEquals("Target[redacted]", target.toString());
        assertFalse(target.toString().contains(target.identifier()));
    }
    final Session session = new Session("synthetic-peer", "a".repeat(64));
    final Target target = new Target("synthetic-native-identifier", "42");
    long now;
    boolean observes = true, updates = true;
    int submissions;
    Completion completion;
    final TelephonySyncSmsNative endpoint = new TelephonySyncSmsNative(session, new Policy() {
        public boolean observes(Session s) { return observes; }
        public boolean updates(Session s) { return updates; }
    }, (t, before, after, c) -> { submissions++; assertEquals(target, t); assertEquals(5, before); completion = c; }, () -> now);
    Snapshot message() { return endpoint.observe(target, 5); }
    Command command(Snapshot s, String request, int status) { return new Command(session, request, s.message(), s.revision(), status); }
    @Test public void noSuccessAtEnqueue() {
        var f = endpoint.apply(command(message(), "r1", 1), 5000); assertFalse(f.isDone());
        completion.complete("native_applied", target, 1);
        assertEquals("native_applied", f.join().code()); assertEquals(1, f.join().observed().status());
        assertEquals(2, f.join().observed().revision());
    }
    @Test public void nativeIdNeverExposed() {
        var snapshot = message(); assertFalse(snapshot.toString().contains(target.identifier()));
        assertFalse(target.toString().contains("42")); assertTrue(snapshot.message().matches("[0-9a-f]{32}"));
    }
    @Test public void noObservationWithoutConsent() { observes = false; assertNull(message()); }
    @Test public void noUpdateWithoutIndependentConsent() {
        var c = command(message(), "r1", 1); updates = false;
        assertEquals("permission_denied", endpoint.apply(c, 1000).join().code()); assertEquals(0, submissions);
    }
    @Test public void foreignSessionCannotUpdate() {
        var s = message(); var c = new Command(new Session("synthetic-other", "b".repeat(64)), "r1", s.message(), 1, 1);
        assertEquals("session_invalid", endpoint.apply(c, 1000).join().code());
    }
    @Test public void staleRevisionRejected() {
        var s = message(); endpoint.observe(target, 4);
        assertEquals("stale_message", endpoint.apply(command(s, "r1", 1), 1000).join().code()); assertEquals(0, submissions);
    }
    @Test public void duplicateAndConflictRetainLedger() {
        var s = message(); var c = command(s, "r1", 1); var f = endpoint.apply(c, 1000);
        completion.complete("native_failed", null, -1);
        assertSame(f, endpoint.apply(c, 1000)); assertEquals("native_failed", f.join().code()); assertEquals(1, submissions);
        assertEquals("request_conflict", endpoint.apply(command(s, "r1", 8), 1000).join().code());
    }
    @Test public void wrongRowCannotComplete() {
        var f = endpoint.apply(command(message(), "r1", 1), 1000);
        completion.complete("native_applied", new Target(target.identifier(), "43"), 1);
        assertEquals("native_mismatch", f.join().code());
    }
    @Test public void wrongStatusCannotComplete() {
        var f = endpoint.apply(command(message(), "r1", 1), 1000); completion.complete("native_applied", target, 8);
        assertEquals("native_mismatch", f.join().code());
    }
    @Test public void timeoutPreventsQueuedWork() {
        var f = endpoint.apply(command(message(), "r1", 1), 30); now = 30;
        assertFalse(completion.active()); assertEquals("outcome_unknown", f.join().code());
        completion.complete("native_applied", target, 1); assertEquals("outcome_unknown", f.join().code());
    }
    @Test public void cancelIsUnknownAndSuppressesQueuedWork() {
        var c = command(message(), "r1", 1); var f = endpoint.apply(c, 1000);
        assertTrue(endpoint.cancel(session, "r1")); assertFalse(completion.active());
        assertEquals("outcome_unknown", f.join().code()); assertSame(f, endpoint.apply(c, 1000)); assertEquals(1, submissions);
    }
    @Test public void revocationStopsQueuedMutationAndClearsIds() {
        var s = message(); var f = endpoint.apply(command(s, "r1", 1), 1000); observes = false;
        assertFalse(completion.active()); assertEquals("permission_revoked", f.join().code()); endpoint.tick();
        observes = true; assertNotEquals(s.message(), message().message());
    }
    @Test public void sameIdentifierCannotBindAnotherRow() {
        message(); assertNull(endpoint.observe(new Target(target.identifier(), "99"), 5));
    }
    @Test public void newSessionCannotReuseId() {
        var s = message(); endpoint.close();
        assertEquals("session_invalid", endpoint.apply(command(s, "r1", 1), 1000).join().code());
    }
    @Test public void immutableFinalStatusCannotBeOverwritten() {
        var s = endpoint.observe(target, 2);
        assertEquals("invalid_message_state", endpoint.apply(command(s, "r1", 8), 1000).join().code());
    }
    @Test public void noResendOrDeliveryCommands() {
        var s = message();
        for (int status : new int[]{0, 2, 3, 4, 5, 6, 7, 100}) {
            try { command(s, "r1", status); fail("nonterminal command accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void perMessageConcurrencyBound() {
        var s = message(); endpoint.apply(command(s, "r1", 1), 1000);
        assertEquals("sms_busy", endpoint.apply(command(s, "r2", 8), 1000).join().code()); assertEquals(1, submissions);
    }
    @Test public void unknownNativeCodeCannotBeSuccess() {
        var f = endpoint.apply(command(message(), "r1", 1), 1000); completion.complete("queued", target, 1);
        assertEquals("outcome_unknown", f.join().code());
    }
    @Test public void capacitiesDoNotEvictReferences() {
        for (int i = 0; i < 512; i++) assertNotNull(endpoint.observe(new Target("synthetic-" + i, "" + i), 5));
        assertNull(endpoint.observe(new Target("synthetic-overflow", "900"), 5));
    }
    @Test public void failedStatusReadbackIsAppliedNotDelivered() {
        var f = endpoint.apply(command(message(), "r1", 8), 1000); completion.complete("native_applied", target, 8);
        assertEquals("native_applied", f.join().code()); assertEquals(8, f.join().observed().status());
    }
    @Test public void newerNativeDeliveryCannotRegressFromDelayedCallback() {
        var f = endpoint.apply(command(message(), "r1", 1), 1000);
        endpoint.observe(target, 2); completion.complete("native_applied", target, 1);
        assertEquals("stale_message", f.join().code()); assertEquals(2, f.join().observed().status());
    }
}
