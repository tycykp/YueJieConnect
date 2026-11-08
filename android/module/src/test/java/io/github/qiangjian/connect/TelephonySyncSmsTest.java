package io.github.qiangjian.connect;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class TelephonySyncSmsTest {
    private final TelephonySyncController.Session session = new TelephonySyncController.Session("synthetic_peer", "a".repeat(64));
    private final AtomicLong clock = new AtomicLong();
    private boolean allowed = true;
    private int sends, disposals;
    private TelephonySyncSms.Completion callback;
    private final TelephonySyncSms sms = new TelephonySyncSms(session, (s, sub) -> allowed && sub == 7,
            c -> new TelephonySyncSms.Prepared() {
                public int parts() { return 2; }
                public void send(TelephonySyncSms.Completion completion) { sends++; callback = completion; }
                public void dispose() { disposals++; }
            }, clock::get);
    private TelephonySyncSms.Command command(String request, String body) {
        return new TelephonySyncSms.Command(session, sms.epoch(), request, 7, "+15550100000", body);
    }
    @Test public void allPartsMustBeSentAndNoDeliveryClaim() {
        var future = sms.send(command("r", "SYNTHETIC ONLY"));
        assertFalse(future.isDone()); callback.sent(1, -1); callback.sent(1, -1); assertFalse(future.isDone());
        callback.sent(0, -1); assertEquals("radio_sent", future.join().code()); assertEquals(2, future.join().sentParts());
        assertEquals(1, disposals);
    }
    @Test public void failurePreservesNativeCodeAndNeverRetries() {
        var c = command("r", "SYNTHETIC ONLY"); var future = sms.send(c); callback.sent(0, 4);
        assertEquals("send_failed", future.join().code()); assertEquals(4, future.join().nativeCode());
        assertSame(future, sms.send(c)); assertEquals(1, sends);
    }
    @Test public void pendingReplayAndConflictingPayload() {
        var c = command("r", "ONE"); var future = sms.send(c); assertSame(future, sms.send(c));
        assertEquals("request_conflict", sms.send(command("r", "TWO")).join().code()); assertEquals(1, sends);
    }
    @Test public void timeoutAndRevokeAreUnknownAfterSubmit() {
        var future = sms.send(command("r", "ONE")); allowed = false; sms.tick();
        assertEquals("outcome_unknown", future.join().code()); callback.sent(0, -1); callback.sent(1, -1);
        assertEquals("outcome_unknown", future.join().code());
    }
    @Test public void lateResultBeforeTickIsUnknown() {
        var future = sms.send(command("r", "ONE")); clock.set(60_000); callback.sent(0, -1);
        assertEquals("outcome_unknown", future.join().code()); assertEquals(1, disposals);
    }
    @Test public void denyAndStaleEpochDoNotTouchRadio() {
        allowed = false; assertEquals("permission_denied", sms.send(command("r", "ONE")).join().code());
        allowed = true;
        var c = new TelephonySyncSms.Command(session, "b".repeat(32), "r", 7, "+15550100000", "ONE");
        assertEquals("session_invalid", sms.send(c).join().code()); assertEquals(0, sends);
    }
    @Test public void closeDisposesPendingWithoutClaimingCarrierCancellation() {
        var future = sms.send(command("r", "ONE")); sms.close();
        assertEquals("outcome_unknown", future.join().code()); assertEquals(1, disposals);
    }
    @Test public void submissionExceptionDoesNotPermitResend() {
        var broken = new TelephonySyncSms(session, (s, sub) -> true, c -> new TelephonySyncSms.Prepared() {
            public int parts() { return 1; }
            public void send(TelephonySyncSms.Completion callback) { sends++; throw new IllegalStateException(); }
            public void dispose() { disposals++; }
        }, clock::get);
        var c = new TelephonySyncSms.Command(session, broken.epoch(), "r", 7, "+15550100000", "ONE");
        assertEquals("outcome_unknown", broken.send(c).join().code()); broken.send(c); assertEquals(1, sends);
    }
    @Test public void payloadCannotLogNumbersOrBody() { assertEquals("SmsCommand[redacted]", command("r", "SECRET_SYNTHETIC").toString()); }
    @Test public void malformedUnicodeAndOversizedPayloadAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> command("r", "\ud800"));
        assertThrows(IllegalArgumentException.class, () -> command("r", "中".repeat(3000)));
    }
    @Test public void expiredTaskNeverSubmitsAndShortBudgetApplies() {
        var c = command("r", "ONE"); assertEquals("deadline_expired", sms.send(c, 0).join().code()); assertEquals(0, sends);
        var future = sms.send(c, 100); clock.set(100); callback.sent(0, -1);
        assertEquals("outcome_unknown", future.join().code());
    }
    @Test public void cancelAfterSubmitIsUnknownNotCancelled() {
        var c = command("r", "ONE"); var future = sms.send(c); sms.cancel(session, c.request());
        assertEquals("outcome_unknown", future.join().code()); assertSame(future, sms.send(c)); assertEquals(1, sends);
    }
    @Test public void preparationCannotExtendTransportDeadline() {
        var slow = new TelephonySyncSms(session, (s, sub) -> true, c -> {
            clock.addAndGet(100);
            return new TelephonySyncSms.Prepared() {
                public int parts() { return 1; }
                public void send(TelephonySyncSms.Completion callback) { sends++; }
                public void dispose() { disposals++; }
            };
        }, clock::get);
        var c = new TelephonySyncSms.Command(session, slow.epoch(), "r", 7, "+15550100000", "ONE");
        assertEquals("deadline_expired", slow.send(c, 100).join().code()); assertEquals(0, sends); assertEquals(1, disposals);
    }
}
