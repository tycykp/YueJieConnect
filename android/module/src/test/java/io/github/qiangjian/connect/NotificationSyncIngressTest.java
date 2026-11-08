package io.github.qiangjian.connect;

import static org.junit.Assert.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;

public class NotificationSyncIngressTest {
    private static final String PEER = "peer", SESSION = NotificationSyncFragmentsTest.SESSION, TRANSFER = NotificationSyncFragmentsTest.TRANSFER;
    private static final class Fixture implements AutoCloseable, NotificationSyncIngress.Executor {
        final AtomicLong clock = new AtomicLong(1000);
        boolean allowed = true, deferred;
        final NotificationSyncIngress ingress = new NotificationSyncIngress(this, (p, s, o) -> allowed, clock::get);
        int executed, cancelled;
        CompletableFuture<byte[]> execution;
        NotificationSyncCodec.Frame frame;
        String logicalRequest;
        long deadline;
        Fixture() { ingress.onProjection(PEER, SESSION); }
        @Override public CompletableFuture<byte[]> execute(String p, String s, String r, String o, byte[] bytes, long deadline) {
            executed++; logicalRequest = r; this.deadline = deadline; frame = NotificationSyncCodec.decode(bytes);
            execution = new CompletableFuture<>();
            if (!deferred) finish(); return execution;
        }
        void finish() { execution.complete(NotificationSyncCodec.encode(NotificationSyncCodec.receipt(frame, frame.kind() == 1 ? "posted" : frame.kind() == 2 ? "removed" : "pending_intent_finished"))); }
        @Override public void cancel(String s, String r) { cancelled++; }
        CompletableFuture<JSONObject> accept(JSONObject payload) { return ingress.accept(PEER, SESSION, NotificationSyncCodec.OP_POST, payload, 10000); }
        @Override public void close() { ingress.close(); }
    }
    static byte[] large() {
        return NotificationSyncCodec.encode(NotificationSyncCodec.posted("synthetic", 1,
                new NotificationSyncCodec.Post("test", "title", "body", "", 0, false, false, new byte[2048], new byte[4096], List.of())));
    }
    static byte[] maximum() {
        var base = new NotificationSyncCodec.Post("test", "title", "", "", 0, false, false, new byte[2048], new byte[4096], List.of());
        int room = NotificationSyncCodec.MAX_BYTES - NotificationSyncCodec.encode(NotificationSyncCodec.posted("synthetic", 1, base)).length;
        return NotificationSyncCodec.encode(NotificationSyncCodec.posted("synthetic", 1,
                new NotificationSyncCodec.Post("test", "title", "t".repeat(2048), "b".repeat(room - 2048), 0, false, false, new byte[2048], new byte[4096], List.of())));
    }
    @Test public void onlyVerifiedLastPartExecutesAndDuplicatesNeverExecuteTwice() throws Exception {
        try (var f = new Fixture()) {
            byte[] bytes = maximum(); assertEquals(10240, bytes.length);
            var parts = NotificationSyncFragments.split(TRANSFER, bytes);
            assertEquals(5, parts.size());
            assertEquals("buffered", f.accept(parts.get(0)).join().getString("state")); assertEquals(0, f.executed);
            f.accept(parts.get(0)).join(); assertEquals(0, f.executed);
            for (int i = 1; i < parts.size() - 1; i++) { f.accept(parts.get(i)).join(); assertEquals(0, f.executed); }
            var result = f.accept(parts.get(4)).join(); assertEquals("complete", result.getString("state"));
            assertEquals(1, f.executed); assertEquals(TRANSFER, f.logicalRequest); assertEquals(7000, f.deadline);
            f.accept(parts.get(4)).join(); assertEquals(1, f.executed);
        }
    }
    @Test public void terminalTombstonesCannotBeEvictedToResurrectAnOldTransfer() throws Exception {
        try (var f = new Fixture()) {
            String hash = NotificationSyncFragments.hash(large());
            for (int i = 0; i < 256; i++) f.accept(NotificationSyncFragments.abort(UUID.randomUUID().toString(), hash)).join();
            var next = NotificationSyncFragments.split(TRANSFER, large()).get(0);
            assertThrows(CompletionException.class, () -> f.accept(next).join()); assertEquals(0, f.executed);
            // A real new session is the only reset of the session-scoped replay ledger.
            f.ingress.onProjection(PEER, "b".repeat(64));
            f.ingress.accept(PEER, "b".repeat(64), "notification_post", next, 9000).join(); assertEquals(0, f.executed);
        }
    }
    @Test public void orderingOperationHashAndPerPartConflictsAreRejectedBeforeDispatch() throws Exception {
        var parts = NotificationSyncFragments.split(TRANSFER, large());
        try (var f = new Fixture()) {
            assertThrows(CompletionException.class, () -> f.accept(parts.get(1)).join());
            f.accept(parts.get(0)).join();
            assertThrows(CompletionException.class, () -> f.accept(parts.get(2)).join());
            assertThrows(CompletionException.class, () -> f.ingress.accept(PEER, SESSION, "notification_remove", parts.get(1), 9000).join());
            var wrong = NotificationSyncFragmentsTest.copy(parts.get(0)).put("data", java.util.Base64.getEncoder().encodeToString(new byte[2048]));
            assertThrows(CompletionException.class, () -> f.accept(wrong).join());
            for (int i = 1; i < parts.size() - 1; i++) f.accept(parts.get(i)).join();
            var tampered = NotificationSyncFragmentsTest.copy(parts.get(parts.size() - 1));
            byte[] bytes = NotificationSyncFragments.parse(tampered).bytes(); bytes[0] ^= 1;
            tampered.put("data", java.util.Base64.getEncoder().encodeToString(bytes));
            assertThrows(CompletionException.class, () -> f.accept(tampered).join()); assertEquals(0, f.executed);
        }
        try (var f = new Fixture()) {
            var remove = NotificationSyncFragments.split(TRANSFER, NotificationSyncCodec.encode(NotificationSyncCodec.removed("target", 1)));
            assertThrows(CompletionException.class, () -> f.accept(remove.get(0)).join()); assertEquals(0, f.executed);
        }
    }
    @Test public void firstPartDeadlineCannotBeExtendedAndSessionOrGrantLossFencesData() throws Exception {
        var parts = NotificationSyncFragments.split(TRANSFER, large());
        try (var f = new Fixture()) {
            f.ingress.accept(PEER, SESSION, "notification_post", parts.get(0), 2500).join();
            f.clock.set(2500); f.ingress.maintain();
            assertThrows(CompletionException.class, () -> f.accept(parts.get(1)).join()); assertEquals(0, f.executed);
        }
        try (var f = new Fixture()) {
            f.accept(parts.get(0)).join(); f.allowed = false; f.ingress.maintain(); f.allowed = true;
            assertThrows(CompletionException.class, () -> f.accept(parts.get(1)).join()); assertEquals(0, f.executed);
        }
        try (var f = new Fixture()) {
            f.accept(parts.get(0)).join(); f.ingress.onProjection(PEER, "b".repeat(64));
            assertThrows(CompletionException.class, () -> f.accept(parts.get(1)).join()); assertEquals(0, f.executed);
        }
    }
    @Test public void abortBeforeFirstAndAfterDispatchFencesLatePartsWithoutClaimingRollback() throws Exception {
        var parts = NotificationSyncFragments.split(TRANSFER, large());
        String hash = NotificationSyncFragments.parse(parts.get(0)).sha256();
        try (var f = new Fixture()) {
            assertEquals("aborted", f.accept(NotificationSyncFragments.abort(TRANSFER, hash)).join().getString("state"));
            assertThrows(CompletionException.class, () -> f.accept(parts.get(0)).join()); assertEquals(0, f.executed);
        }
        try (var f = new Fixture()) {
            f.deferred = true;
            for (int i = 0; i < parts.size() - 1; i++) f.accept(parts.get(i)).join();
            var finalResult = f.accept(parts.get(parts.size() - 1)); assertFalse(finalResult.isDone()); assertEquals(1, f.executed);
            f.accept(NotificationSyncFragments.abort(TRANSFER, hash)).join(); f.finish();
            assertThrows(CompletionException.class, finalResult::join); assertEquals(1, f.cancelled);
            assertThrows(CompletionException.class, () -> f.accept(parts.get(parts.size() - 1)).join()); assertEquals(1, f.executed);
        }
    }
    @Test public void activeCapacityAndTerminalActionReplayAreBounded() throws Exception {
        try (var f = new Fixture()) {
            for (int i = 0; i < 8; i++) f.accept(NotificationSyncFragments.split(UUID.randomUUID().toString(), large()).get(0)).join();
            var ninth = NotificationSyncFragments.split(UUID.randomUUID().toString(), large()).get(0);
            assertThrows(CompletionException.class, () -> f.accept(ninth).join()); assertEquals(0, f.executed);
            f.clock.set(7000); f.ingress.maintain(); f.accept(ninth).join();
        }
        try (var f = new Fixture()) {
            var action = NotificationSyncCodec.action("target", 1, "0", "");
            var part = NotificationSyncFragments.split(TRANSFER, NotificationSyncCodec.encode(action)).get(0);
            f.ingress.accept(PEER, SESSION, "notification_action", part, 9000).join();
            f.ingress.accept(PEER, SESSION, "notification_action", part, 9000).join(); assertEquals(1, f.executed);
            f.ingress.cancel(SESSION, TRANSFER);
            assertThrows(CompletionException.class, () -> f.ingress.accept(PEER, SESSION, "notification_action", part, 9000).join()); assertEquals(1, f.executed);
        }
    }
}
